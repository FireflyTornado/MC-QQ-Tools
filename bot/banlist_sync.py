import hashlib
import json
import sqlite3
from collections.abc import Mapping
from contextlib import closing
from pathlib import Path

from database import MAX_AUTH_VERSION, update_auth_version, valid_mc_name


class UploadConflict(Exception):
    """An upload is outdated, out of sequence, or conflicts with an accepted report."""


def read_generation(path: Path) -> int:
    with closing(sqlite3.connect(f"{path.resolve().as_uri()}?mode=ro", uri=True, timeout=5)) as conn:
        return _read_cursor(conn)[0]


def store_upload(
    path: Path, *, generation: int, client_id: str, sequence: int, entries: Mapping[str, int | None],
) -> int:
    bans = normalize_ban_snapshot(entries)
    digest = hashlib.sha256(json.dumps(bans, sort_keys=True, separators=(",", ":")).encode("ascii")).hexdigest()
    with closing(sqlite3.connect(f"{path.resolve().as_uri()}?mode=rw", uri=True, timeout=5)) as conn, conn:
        conn.execute("BEGIN IMMEDIATE")
        old_generation, old_client_id, old_sequence, previous_digest = _read_cursor(conn)
        if generation == old_generation and client_id == old_client_id:
            if sequence == old_sequence and digest == previous_digest:
                return 0
            if sequence != old_sequence + 1:
                raise UploadConflict
        elif generation != old_generation + 1 or sequence != 1 or client_id == old_client_id:
            raise UploadConflict

        frozen = apply_normalized_bans(conn, bans)
        conn.execute(
            """
            UPDATE auth_state SET ban_generation = ?, ban_client_id = ?, ban_sequence = ?, ban_digest = ?
            WHERE id = 1
            """,
            (generation, client_id, sequence, digest),
        )
        return frozen


def _read_cursor(conn: sqlite3.Connection) -> tuple[int, str, int, str]:
    row = conn.execute(
        "SELECT ban_generation, ban_client_id, ban_sequence, ban_digest FROM auth_state WHERE id = 1"
    ).fetchone()
    if row is None:
        raise sqlite3.IntegrityError("Missing authorization state")
    return row


def normalize_ban_snapshot(entries: Mapping[str, int | None]) -> dict[str, int | None]:
    """Use the longest active ban for each case-insensitive player name."""
    bans: dict[str, int | None] = {}
    for name, expiration in entries.items():
        if not isinstance(name, str) or not valid_mc_name(name):
            raise ValueError("Invalid player name in ban snapshot")
        if expiration is not None and (type(expiration) is not int or not 0 <= expiration <= MAX_AUTH_VERSION):
            raise ValueError("Invalid expiration in ban snapshot")
        name_key = name.lower()
        if name_key in bans:
            previous = bans[name_key]
            expiration = max(previous, expiration) if previous is not None and expiration is not None else None
        bans[name_key] = expiration
    return bans


def apply_normalized_bans(conn: sqlite3.Connection, bans: dict[str, int | None]) -> int:
    """Apply a normalized banlist inside the caller's SQLite write transaction."""
    frozen_count = 0
    existing = {
        name: (state, expiration, binding_id)
        for name, state, expiration, binding_id in conn.execute(
            "SELECT mc_name_key, state, expires_at, binding_id FROM server_bans"
        )
    }
    for name, expiration in bans.items():
        old = existing.get(name)
        if old is None:
            conn.execute(
                """
                INSERT INTO server_bans (mc_name_key, state, expires_at,
                    binding_id, group_id, qq_id, mc_name, bound_at, updated_at)
                SELECT ?, 'BANNED', ?, id, group_id, qq_id, mc_name, bound_at, updated_at
                FROM (SELECT 1)
                LEFT JOIN offline_bindings ON mc_name_key = ? AND status = 'ACTIVE'
                """,
                (name, expiration, name),
            )
            frozen_count += conn.execute(
                "DELETE FROM offline_bindings WHERE mc_name_key = ? AND status = 'ACTIVE'", (name,),
            ).rowcount
        elif old[:2] != ("BANNED", expiration):
            conn.execute(
                """
                UPDATE server_bans SET revision = revision + (state != 'BANNED'),
                    state = 'BANNED', expires_at = ? WHERE mc_name_key = ?
                """,
                (expiration, name),
            )
    for name in existing.keys() - bans.keys():
        state, _, binding_id = existing[name]
        if state != "BANNED":
            continue
        if binding_id is None:
            conn.execute("DELETE FROM server_bans WHERE mc_name_key = ?", (name,))
        else:
            conn.execute(
                """
                UPDATE server_bans SET state = 'PENDING_MEMBERSHIP_CHECK', expires_at = NULL,
                    revision = revision + 1 WHERE mc_name_key = ?
                """,
                (name,),
            )
    if frozen_count:
        update_auth_version(conn)
    return frozen_count
