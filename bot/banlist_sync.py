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

        # Filter under the write lock. The digest still describes the unfiltered report.
        now = time.time_ns() // 1_000_000
        active_entries = {name: expiration for name, expiration in bans.items() if expiration is None or expiration > now}
        active_profiles = {
            uuid: (name, expiration) for uuid, (name, expiration) in (normalized_profiles or {}).items()
            if expiration is None or expiration > now
        }
        frozen = apply_normalized_bans(conn, active_entries, active_profiles)
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


def _resolve_profiles(
    entries: dict[str, int | None],
    profiles: dict[str, tuple[str | None, int | None]] | None,
    old_names: dict[str, str],
) -> dict[str, ProfileRow]:
    profile_rows: dict[str, ProfileRow] = {
        uuid: (name, None, "KNOWN_PROFILE") for uuid, name in old_names.items() if name in entries
    }
    for uuid, (reported_name, expiration) in (profiles or {}).items():
        name = reported_name if reported_name is not None else old_names.get(uuid)
        profile_rows[uuid] = (name, expiration, "BANNED")
    return profile_rows


def _store_name_bans(conn: sqlite3.Connection, entries: dict[str, int | None]) -> None:
    old_entries = dict(conn.execute("SELECT mc_name_key, expires_at FROM server_bans WHERE profile_uuid IS NULL"))
    removed = old_entries.keys() - entries.keys()
    conn.executemany("DELETE FROM server_bans WHERE profile_uuid IS NULL AND mc_name_key = ?", ((name,) for name in removed))
    conn.executemany(
        """
        INSERT INTO server_bans (mc_name_key, expires_at, state) VALUES (?, ?, 'BANNED')
        ON CONFLICT(mc_name_key) WHERE profile_uuid IS NULL DO UPDATE SET expires_at = excluded.expires_at
        """,
        ((name, expiration) for name, expiration in entries.items() if name not in old_entries or old_entries[name] != expiration),
    )


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


def apply_normalized_bans(
    conn: sqlite3.Connection, entries: dict[str, int | None],
    profiles: dict[str, tuple[str | None, int | None]] | None = None,
) -> int:
    """Apply an effective snapshot and its binding changes in the caller's write transaction."""
    old_profiles = {
        uuid: (name, expiration, state)
        for uuid, name, expiration, state in conn.execute(
            "SELECT profile_uuid, mc_name_key, expires_at, state FROM server_bans WHERE profile_uuid IS NOT NULL"
        )
    }
    old_names = {uuid: name for uuid, (name, _, _) in old_profiles.items() if name is not None}
    profile_rows = _resolve_profiles(entries, profiles, old_names)
    _store_name_bans(conn, entries)
    _store_profiles(conn, old_profiles, profile_rows)
    return _sync_frozen_bindings(conn)


def _sync_frozen_bindings(conn: sqlite3.Connection) -> int:
    frozen_count = conn.execute("""
        INSERT INTO frozen_bindings (binding_id, group_id, qq_id, mc_name, mc_name_key, bound_at, updated_at, state)
        SELECT id, group_id, qq_id, mc_name, mc_name_key, bound_at, updated_at, 'FROZEN'
        FROM offline_bindings AS binding
        WHERE binding.status = 'ACTIVE' AND EXISTS (
            SELECT 1 FROM server_bans AS ban WHERE ban.state = 'BANNED' AND ban.mc_name_key = binding.mc_name_key
        )
    """).rowcount
    if frozen_count:
        conn.execute("""
            DELETE FROM offline_bindings AS binding
            WHERE binding.status = 'ACTIVE' AND EXISTS (
                SELECT 1 FROM server_bans AS ban WHERE ban.state = 'BANNED' AND ban.mc_name_key = binding.mc_name_key
            )
        """)
        update_auth_version(conn)

    conn.execute("""
        UPDATE frozen_bindings AS binding SET state = 'FROZEN', revision = revision + 1
        WHERE binding.state = 'PENDING_MEMBERSHIP_CHECK' AND EXISTS (
            SELECT 1 FROM server_bans AS ban WHERE ban.state = 'BANNED' AND ban.mc_name_key = binding.mc_name_key
        )
    """)
    conn.execute("""
        UPDATE frozen_bindings AS binding SET state = 'PENDING_MEMBERSHIP_CHECK', revision = revision + 1
        WHERE binding.state = 'FROZEN' AND NOT EXISTS (
            SELECT 1 FROM server_bans AS ban WHERE ban.state = 'BANNED' AND ban.mc_name_key = binding.mc_name_key
        )
    """)
    return frozen_count
