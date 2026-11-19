import sqlite3
import time
from collections.abc import Mapping, Set
from contextlib import closing
from dataclasses import dataclass
from typing import Literal

from banlist_sync import apply_normalized_bans, normalize_ban_snapshot
from database import DB_PATH, update_auth_version, valid_mc_name
from notify_client import notify_auth_updated


@dataclass(frozen=True)
class MembershipAudit:
    version: int
    bindings: dict[str, int]
    pending: dict[str, tuple[str, int]]


@dataclass(frozen=True)
class DepartureCheck:
    binding_id: int
    mc_name: str
    version: int


def _active_binding(conn: sqlite3.Connection, group_id: str, qq_id: str) -> tuple[int, str, int] | None:
    return conn.execute(
        """
        SELECT id, mc_name, MAX(bound_at, updated_at)
        FROM offline_bindings WHERE group_id = ? AND qq_id = ? AND status = 'ACTIVE'
        """,
        (group_id, qq_id),
    ).fetchone()


def _reserved_binding(conn: sqlite3.Connection, group_id: str, qq_id: str) -> tuple[str, str] | None:
    return conn.execute(
        """
        SELECT CASE state WHEN 'FROZEN' THEN 'frozen' ELSE 'pending' END, mc_name
        FROM frozen_bindings WHERE group_id = ? AND qq_id = ?
        """,
        (group_id, qq_id),
    ).fetchone()


def query_binding(group_id: str, qq_id: str) -> tuple[str, str | None]:
    with closing(sqlite3.connect(DB_PATH, timeout=10)) as conn, conn:
        conn.execute("BEGIN")
        row = _active_binding(conn, group_id, qq_id)
        if row:
            return "success", row[1]
        return _reserved_binding(conn, group_id, qq_id) or ("not_bound", None)


def get_binding(group_id: str, qq_id: str) -> str | None:
    """查询指定 QQ 当前具备授权的游戏 ID。"""
    status, name = query_binding(group_id, qq_id)
    return name if status == "success" else None


def bind_player(group_id: str, qq_id: str, mc_name: str) -> tuple[str, str | None]:
    """绑定玩家，返回操作状态和关联的游戏 ID。"""
    if not valid_mc_name(mc_name):
        return "invalid_name", None

    name_key = mc_name.lower()
    now = int(time.time())
    with closing(sqlite3.connect(DB_PATH, timeout=10)) as conn, conn:
        conn.execute("BEGIN IMMEDIATE")
        existing = conn.execute(
            """
            SELECT mc_name FROM offline_bindings WHERE group_id = ? AND qq_id = ? AND status = 'ACTIVE'
            UNION ALL SELECT mc_name FROM frozen_bindings WHERE group_id = ? AND qq_id = ? LIMIT 1
            """,
            (group_id, qq_id, group_id, qq_id),
        ).fetchone()
        if existing:
            return "already_bound", existing[0]

        ban = conn.execute(
            "SELECT 1 FROM server_bans WHERE state = 'BANNED' AND mc_name_key = ? LIMIT 1", (name_key,),
        ).fetchone()
        if ban:
            return "banned_name", None
        occupied = conn.execute(
            """
            SELECT 1 FROM offline_bindings WHERE mc_name_key = ? AND status = 'ACTIVE'
            UNION ALL SELECT 1 FROM frozen_bindings WHERE mc_name_key = ? LIMIT 1
            """,
            (name_key, name_key),
        ).fetchone()
        if occupied:
            return "name_taken", None

        conn.execute(
            """
            INSERT INTO offline_bindings (group_id, qq_id, mc_name, mc_name_key, status, bound_at, updated_at)
            VALUES (?, ?, ?, ?, 'ACTIVE', ?, ?)
            """,
            (group_id, qq_id, mc_name, name_key, now, now),
        )
        update_auth_version(conn)

    notify_auth_updated()
    return "success", mc_name


def _request_unbind(
    group_id: str, qq_id: str, left_at: int | None, *, only_same_second: bool = False,
) -> tuple[str, str | None] | DepartureCheck:
    with closing(sqlite3.connect(DB_PATH, timeout=10)) as conn, conn:
        conn.execute("BEGIN IMMEDIATE")
        row = _active_binding(conn, group_id, qq_id)
        if not row:
            return _reserved_binding(conn, group_id, qq_id) or ("not_bound", None)
        binding_id, mc_name, authorized_at = row
        if left_at is not None:
            if authorized_at == left_at:
                return DepartureCheck(binding_id, mc_name, _audit_version(conn))
            if only_same_second or authorized_at > left_at:
                return "not_bound", None
        _delete_active_bindings(conn, group_id, {qq_id: binding_id})
        update_auth_version(conn)

    notify_auth_updated()
    return "success", mc_name


def request_unbind(group_id: str, qq_id: str, *, left_at: int | None = None) -> tuple[str, str | None]:
    result = _request_unbind(group_id, qq_id, left_at)
    if isinstance(result, DepartureCheck):
        return "verify_membership", result.mc_name
    return result


def unbind_player(group_id: str, qq_id: str, *, left_at: int | None = None) -> str | None:
    """删除当前有效绑定，并更新权限版本号。"""
    status, name = request_unbind(group_id, qq_id, left_at=left_at)
    return name if status == "success" else None


def handle_departure(
    group_id: str, qq_id: str, left_at: int, *, only_same_second: bool = False,
) -> DepartureCheck | Literal["revoked", "retained", "no_binding"]:
    result = _request_unbind(group_id, qq_id, left_at, only_same_second=only_same_second)
    if isinstance(result, DepartureCheck):
        return result
    status, _ = result
    if status == "success":
        return "revoked"
    if status in {"frozen", "pending"}:
        return "retained"
    return "no_binding"


def apply_ban_snapshot(entries: Mapping[str, int | None]) -> int:
    """Apply the effective player banlist with Unix millisecond expirations."""
    bans = normalize_ban_snapshot(entries)
    with closing(sqlite3.connect(DB_PATH, timeout=10)) as conn, conn:
        conn.execute("BEGIN IMMEDIATE")
        frozen_count = apply_normalized_bans(conn, bans)
    if frozen_count:
        notify_auth_updated()
    return frozen_count


def membership_audit_snapshot(group_id: str, *, only_pending: bool = False) -> MembershipAudit:
    """Read authorization and pending checks from one SQLite snapshot."""
    with closing(sqlite3.connect(DB_PATH, timeout=10)) as conn, conn:
        conn.execute("BEGIN")
        version = _audit_version(conn)
        pending = {
            qq_id: (mc_name_key, revision)
            for qq_id, mc_name_key, revision in conn.execute(
                """
                SELECT qq_id, mc_name_key, revision FROM frozen_bindings
                WHERE group_id = ? AND state = 'PENDING_MEMBERSHIP_CHECK'
                """,
                (group_id,),
            )
        }
        bindings = {} if only_pending else dict(conn.execute(
            "SELECT qq_id, id FROM offline_bindings WHERE group_id = ? AND status = 'ACTIVE'", (group_id,),
        ))
        return MembershipAudit(version, bindings, pending)


def binding_audit_snapshot(group_id: str) -> tuple[int, dict[str, int]]:
    audit = membership_audit_snapshot(group_id)
    return audit.version, audit.bindings


def reconcile_checked_bindings(
    group_id: str, audit: MembershipAudit, members: Set[str], checked_at: int,
) -> int | None:
    missing = {qq_id: audit.bindings[qq_id] for qq_id in audit.bindings.keys() - members}
    return _finish_membership_audit(group_id, audit.version, missing, audit.pending, members, checked_at)


def revoke_checked_bindings(group_id: str, version: int, missing: dict[str, int]) -> int | None:
    return _finish_membership_audit(group_id, version, missing, {}, frozenset(), 0)


def _finish_membership_audit(
    group_id: str,
    version: int,
    missing: dict[str, int],
    pending: dict[str, tuple[str, int]],
    members: Set[str],
    checked_at: int,
) -> int | None:
    if not missing and not pending:
        return 0
    with closing(sqlite3.connect(DB_PATH, timeout=10)) as conn, conn:
        conn.execute("BEGIN IMMEDIATE")
        # Some supported legacy tables can reuse a deleted row ID.
        if _audit_version(conn) != version:
            return None
        deleted = _delete_active_bindings(conn, group_id, missing)
        restored, released = _resolve_pending_bindings(conn, group_id, pending, members, checked_at)
        if deleted or restored:
            update_auth_version(conn)
    if deleted or restored:
        notify_auth_updated()
    return deleted + released


def _resolve_pending_bindings(
    conn: sqlite3.Connection,
    group_id: str,
    pending: dict[str, tuple[str, int]],
    members: Set[str],
    checked_at: int,
) -> tuple[int, int]:
    restored = released = 0
    for qq_id, (name_key, revision) in pending.items():
        saved = conn.execute(
            """
            SELECT binding_id, mc_name, bound_at, updated_at FROM frozen_bindings
            WHERE mc_name_key = ? AND group_id = ? AND qq_id = ?
              AND state = 'PENDING_MEMBERSHIP_CHECK' AND revision = ?
            """,
            (name_key, group_id, qq_id, revision),
        ).fetchone()
        if not saved:
            continue
        if qq_id in members:
            binding_id, name, bound_at, updated_at = saved
            if conn.execute("SELECT 1 FROM offline_bindings WHERE id = ?", (binding_id,)).fetchone():
                binding_id = None
            # A delayed departure from before the verified roster cannot revoke the restored binding.
            conn.execute(
                """
                INSERT INTO offline_bindings (id, group_id, qq_id, mc_name, mc_name_key, status, bound_at, updated_at)
                VALUES (?, ?, ?, ?, ?, 'ACTIVE', ?, ?)
                """,
                (binding_id, group_id, qq_id, name, name_key, bound_at, max(updated_at, checked_at)),
            )
            restored += 1
        else:
            released += 1
        conn.execute("DELETE FROM frozen_bindings WHERE mc_name_key = ?", (name_key,))
    return restored, released


def _audit_version(conn: sqlite3.Connection) -> int:
    row = conn.execute("SELECT version FROM auth_state WHERE id = 1").fetchone()
    if row is None or type(row[0]) is not int or row[0] < 0:
        raise sqlite3.IntegrityError("授权版本记录无效")
    return row[0]


def _delete_active_bindings(
    conn: sqlite3.Connection, group_id: str, bindings: dict[str, int],
) -> int:
    return conn.executemany(
        """
        DELETE FROM offline_bindings
        WHERE group_id = ? AND qq_id = ? AND id = ? AND status = 'ACTIVE'
        """,
        ((group_id, qq_id, binding_id) for qq_id, binding_id in bindings.items()),
    ).rowcount
