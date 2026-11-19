import hashlib
import json
import sqlite3
import time
from collections.abc import Mapping
from contextlib import closing
from pathlib import Path
from typing import TypedDict

from database import MAX_AUTH_VERSION, PROFILE_UUID_PATTERN, update_auth_version, valid_mc_name


class UploadConflict(Exception):
    """An upload is outdated, out of sequence, or conflicts with an accepted report."""


class GenerationClaimed(UploadConflict):
    """The first report targeted the current generation owned by another client."""


class ReportedProfile(TypedDict):
    name: str | None
    expires_at: int | None


ProfileRow = tuple[str | None, int | None, str]


def read_generation(path: Path) -> int:
    with closing(sqlite3.connect(f"{path.resolve().as_uri()}?mode=ro", uri=True, timeout=5)) as conn:
        return _read_cursor(conn)[0]


def store_upload(
    path: Path, *, generation: int, client_id: str, sequence: int, entries: Mapping[str, int | None],
    profiles: Mapping[str, ReportedProfile] | None = None,
) -> int:
    bans = normalize_ban_snapshot(entries)
    normalized_profiles = _normalize_profiles(profiles) if profiles is not None else None
    payload = bans if normalized_profiles is None else {"entries": bans, "profiles": normalized_profiles}
    digest = hashlib.sha256(json.dumps(payload, sort_keys=True, separators=(",", ":")).encode("ascii")).hexdigest()
    with closing(sqlite3.connect(f"{path.resolve().as_uri()}?mode=rw", uri=True, timeout=5)) as conn, conn:
        conn.execute("BEGIN IMMEDIATE")
        old_generation, old_client_id, old_sequence, previous_digest = _read_cursor(conn)
        if generation == old_generation:
            if client_id != old_client_id:
                if sequence == 1:
                    raise GenerationClaimed
                raise UploadConflict
            if sequence == old_sequence and digest == previous_digest:
                return 0
            if sequence != old_sequence + 1:
                raise UploadConflict
        elif generation != old_generation + 1 or sequence != 1 or client_id == old_client_id:
            raise UploadConflict

        old_profiles = {
            uuid: (name, expiration, state)
            for uuid, name, expiration, state in conn.execute(
                "SELECT profile_uuid, mc_name_key, expires_at, state FROM server_bans WHERE profile_uuid IS NOT NULL"
            )
        }
        old_names = {uuid: name for uuid, (name, _, _) in old_profiles.items() if name is not None}
        active, profile_rows = _effective_report(bans, normalized_profiles, old_names)
        frozen = apply_normalized_bans(conn, active)
        _store_profiles(conn, old_profiles, profile_rows)
        conn.execute(
            "UPDATE banlist_state SET generation = ?, client_id = ?, sequence = ?, digest = ? WHERE id = 1",
            (generation, client_id, sequence, digest),
        )
        return frozen


def _read_cursor(conn: sqlite3.Connection) -> tuple[int, str, int, str]:
    row = conn.execute(
        "SELECT generation, client_id, sequence, digest FROM banlist_state WHERE id = 1"
    ).fetchone()
    if row is None:
        raise sqlite3.IntegrityError("Missing banlist state")
    return row


def normalize_ban_snapshot(entries: Mapping[str, int | None]) -> dict[str, int | None]:
    """Use the longest active ban for each case-insensitive player name."""
    bans: dict[str, int | None] = {}
    for name, expiration in entries.items():
        if not isinstance(name, str) or not valid_mc_name(name):
            raise ValueError("Invalid player name in ban snapshot")
        _validate_expiration(expiration)
        _merge_ban(bans, name.lower(), expiration)
    return bans


def _normalize_profiles(profiles: Mapping[str, ReportedProfile]) -> dict[str, tuple[str | None, int | None]]:
    normalized = {}
    for uuid, profile in profiles.items():
        if not PROFILE_UUID_PATTERN.fullmatch(uuid):
            raise ValueError("Invalid profile UUID in ban snapshot")
        name, expiration = profile["name"], profile["expires_at"]
        if name is not None and not valid_mc_name(name):
            raise ValueError("Invalid profile name in ban snapshot")
        _validate_expiration(expiration)
        normalized[uuid] = (None if name is None else name.lower(), expiration)
    return normalized


def _validate_expiration(expiration: int | None) -> None:
    if expiration is not None and (type(expiration) is not int or not 0 <= expiration <= MAX_AUTH_VERSION):
        raise ValueError("Invalid expiration in ban snapshot")


def _merge_ban(bans: dict[str, int | None], name: str, expiration: int | None) -> None:
    if name in bans:
        previous = bans[name]
        expiration = max(previous, expiration) if previous is not None and expiration is not None else None
    bans[name] = expiration


def _effective_report(
    entries: dict[str, int | None],
    profiles: dict[str, tuple[str | None, int | None]] | None,
    old_names: dict[str, str],
) -> tuple[dict[str, int | None], dict[str, ProfileRow]]:
    # Sample after acquiring the SQLite write lock. The digest still describes the unfiltered report.
    now = time.time_ns() // 1_000_000
    active = {name: expiration for name, expiration in entries.items() if expiration is None or expiration > now}
    profile_rows: dict[str, ProfileRow] = {
        uuid: (name, None, "KNOWN_PROFILE") for uuid, name in old_names.items() if name in active
    }
    for uuid, (reported_name, expiration) in (profiles or {}).items():
        if expiration is not None and expiration <= now:
            continue
        name = reported_name if reported_name is not None else old_names.get(uuid)
        profile_rows[uuid] = (name, expiration, "BANNED")
        if name is not None:
            _merge_ban(active, name, expiration)
    return active, profile_rows


def _store_profiles(
    conn: sqlite3.Connection, old_profiles: dict[str, ProfileRow], profiles: dict[str, ProfileRow],
) -> None:
    removed = old_profiles.keys() - profiles.keys()
    conn.executemany("DELETE FROM server_bans WHERE profile_uuid = ?", ((uuid,) for uuid in removed))
    conn.executemany(
        """
        INSERT INTO server_bans (profile_uuid, mc_name_key, expires_at, state) VALUES (?, ?, ?, ?)
        ON CONFLICT(profile_uuid) DO UPDATE SET mc_name_key = excluded.mc_name_key,
            expires_at = excluded.expires_at, state = excluded.state
        """,
        ((uuid, *profile) for uuid, profile in profiles.items() if old_profiles.get(uuid) != profile),
    )


def apply_normalized_bans(conn: sqlite3.Connection, bans: dict[str, int | None]) -> int:
    """Apply a normalized banlist inside the caller's SQLite write transaction."""
    frozen_count = 0
    existing = {
        name: (state, expiration, binding_id)
        for name, state, expiration, binding_id in conn.execute(
            "SELECT mc_name_key, state, expires_at, binding_id FROM server_bans WHERE profile_uuid IS NULL"
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
                    state = 'BANNED', expires_at = ? WHERE profile_uuid IS NULL AND mc_name_key = ?
                """,
                (expiration, name),
            )
    for name in existing.keys() - bans.keys():
        state, _, binding_id = existing[name]
        if state != "BANNED":
            continue
        if binding_id is None:
            conn.execute("DELETE FROM server_bans WHERE profile_uuid IS NULL AND mc_name_key = ?", (name,))
        else:
            conn.execute(
                """
                UPDATE server_bans SET state = 'PENDING_MEMBERSHIP_CHECK', expires_at = NULL,
                    revision = revision + 1 WHERE profile_uuid IS NULL AND mc_name_key = ?
                """,
                (name,),
            )
    if frozen_count:
        update_auth_version(conn)
    return frozen_count
