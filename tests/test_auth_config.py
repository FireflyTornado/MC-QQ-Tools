import asyncio
import importlib
import json
import os
import shutil
import socket
import sqlite3
import subprocess
import sys
import tempfile
import unittest
from contextlib import asynccontextmanager, closing
from pathlib import Path
from urllib.error import URLError
from unittest.mock import patch

import uvicorn
from websockets.asyncio.client import connect

BOT_DIR = Path(__file__).resolve().parents[1] / "bot"
sys.path.insert(0, str(BOT_DIR))

import app_config
import binding_service
import database
import notify_client
import runtime_status

API_TOKEN = "test-only.api~token+="
NOTIFY_TOKEN = "test-only.notify-token"
BANLIST_TOKEN = "test-only.banlist-token"
GROUP_ID = "12345"
VERSION = 1_800_000_000_001
ENV = {
    "MC_API_TOKEN": API_TOKEN,
    "MC_NOTIFY_TOKEN": NOTIFY_TOKEN,
    "TARGET_GROUP_ID": GROUP_ID,
}
SNAPSHOT_PATH = "/internal/mc/snapshot"
NOTIFY_PATH = "/internal/mc/notify"
WS_PATH = "/internal/mc/ws"
BANLIST_PATH = "/internal/mc/banlist"


def bearer(token):
    return f"Bearer {token}".encode("ascii")


def scope(kind, path, authorization):
    return {
        "type": kind,
        "asgi": {"version": "3.0", "spec_version": "2.3"},
        "http_version": "1.1",
        "scheme": "ws" if kind == "websocket" else "http",
        "path": path,
        "root_path": "",
        "query_string": b"",
        "headers": [(b"authorization", value) for value in authorization],
        "client": ("127.0.0.1", 50000),
        "server": ("127.0.0.1", 18765),
    }


class ApiAuthTests(unittest.IsolatedAsyncioTestCase):
    def setUp(self):
        temp = tempfile.TemporaryDirectory()
        self.addCleanup(temp.cleanup)
        root = Path(temp.name)
        db_path = root / "auth.db"
        database.init_database(db_path)
        with closing(sqlite3.connect(db_path)) as conn, conn:
            conn.execute("UPDATE auth_state SET version = ?", (VERSION,))
            conn.executemany(
                """
                INSERT INTO offline_bindings
                    (group_id, qq_id, mc_name, mc_name_key, status, bound_at, updated_at)
                VALUES (?, ?, ?, ?, ?, 1, 1)
                """,
                [
                    (GROUP_ID, "1", "Steve", "steve", "ACTIVE"),
                    (GROUP_ID, "2", "Alex", "alex", "ACTIVE"),
                    (GROUP_ID, "3", "Former", "former", "REVOKED"),
                    ("99999", "4", "Other", "other", "ACTIVE"),
                ],
            )
        env_file = root / ".env"
        env_file.write_text("TARGET_GROUP_ID=00" + GROUP_ID + "\n", encoding="utf-8")
        with patch.object(app_config, "ENV_FILE", env_file), patch.dict(
            os.environ, {
                "MC_API_TOKEN": API_TOKEN, "MC_NOTIFY_TOKEN": NOTIFY_TOKEN, "MC_BANLIST_TOKEN": BANLIST_TOKEN,
            }, clear=True
        ):
            self.api = importlib.import_module("mc_api")
        self.addCleanup(sys.modules.pop, "mc_api", None)
        self.api.DB_PATH = db_path

    async def request(self, path, authorization=(), method="GET", body=b""):
        sent = []

        async def receive():
            return {"type": "http.request", "body": body, "more_body": False}

        async def send(message):
            sent.append(message)

        await self.api.app(
            {**scope("http", path, authorization), "method": method}, receive, send
        )
        headers = dict(sent[0]["headers"])
        return sent[0]["status"], headers, json.loads(sent[-1]["body"])

    async def test_snapshot_keeps_contract_and_accepts_standard_bearer_syntax(self):
        for header in (bearer(API_TOKEN), f"\tbEaReR   {API_TOKEN} \t".encode("ascii")):
            with self.subTest(header_syntax=header.startswith(b"Bearer ")):
                status, headers, payload = await self.request(SNAPSHOT_PATH, [header])
                self.assertEqual(status, 200)
                self.assertEqual(headers[b"cache-control"], b"no-store")
                self.assertEqual(payload, {"version": VERSION, "active_names": ["alex", "steve"]})
                self.assertIsInstance(payload["version"], int)

    async def upload_banlist(self, generation, sequence, entries, client_id="a" * 32, *, profiles=None):
        payload = {"generation": generation, "client_id": client_id, "sequence": sequence, "entries": entries}
        if profiles is not None:
            payload["profiles"] = profiles
        return await self.request(BANLIST_PATH, [bearer(BANLIST_TOKEN)], "PUT", json.dumps(payload).encode())

    async def test_banlist_upload_freezes_broadcasts_and_only_restores_after_membership_check(self):
        status, headers, cursor = await self.request(BANLIST_PATH, [bearer(BANLIST_TOKEN)])
        self.assertEqual((status, headers[b"cache-control"], cursor), (200, b"no-store", {"generation": 0}))

        async with self.websocket() as (updates, _):
            status, _, reply = await self.upload_banlist(1, 1, {"Steve": None})
            self.assertEqual((status, reply), (200, {"generation": 1, "sequence": 1}))
            _, _, frozen = await self.request(SNAPSHOT_PATH, [bearer(API_TOKEN)])
            self.assertEqual(frozen["active_names"], ["alex"])
            self.assertGreater(frozen["version"], VERSION)
            update = json.loads((await asyncio.wait_for(updates.get(), 2))["text"])
            self.assertEqual(update, {"type": "auth_updated", "version": frozen["version"]})

            self.assertEqual((await self.upload_banlist(1, 1, {"STEVE": None}))[0], 200)
            self.assertEqual((await self.upload_banlist(1, 1, {}))[0], 409)
            self.assertEqual((await self.upload_banlist(1, 3, {}))[0], 409)
            self.assertEqual((await self.upload_banlist(1, 2, {}))[0], 200)
            self.assertEqual((await self.request(SNAPSHOT_PATH, [bearer(API_TOKEN)]))[2], frozen)
            self.assertTrue(updates.empty())

        with (patch.object(binding_service, "DB_PATH", self.api.DB_PATH),
              patch.object(binding_service, "notify_auth_updated")):
            self.assertEqual(binding_service.request_unbind(GROUP_ID, "1"), ("pending", "Steve"))
            audit = binding_service.membership_audit_snapshot(GROUP_ID, only_pending=True)
            binding_service.reconcile_checked_bindings(GROUP_ID, audit, {"1"}, 2)
        restored = (await self.request(SNAPSHOT_PATH, [bearer(API_TOKEN)]))[2]
        self.assertEqual(restored["active_names"], ["alex", "steve"])
        self.assertGreater(restored["version"], frozen["version"])

    async def test_new_banlist_session_fences_old_writers_and_cursor_survives_api_restart(self):
        self.assertEqual((await self.request(BANLIST_PATH, [bearer(BANLIST_TOKEN)]))[2], {"generation": 0})
        self.assertEqual((await self.upload_banlist(1, 1, {"Steve": None}))[0], 200)
        status, headers, _ = await self.upload_banlist(1, 1, {}, client_id="b" * 32)
        self.assertEqual(status, 409)
        self.assertEqual(headers[b"x-mcqq-banlist-conflict"], b"generation-claimed")
        status, headers, _ = await self.upload_banlist(1, 2, {}, client_id="b" * 32)
        self.assertEqual(status, 409)
        self.assertNotIn(b"x-mcqq-banlist-conflict", headers)
        self.assertEqual((await self.upload_banlist(2, 1, {"Alex": None}, client_id="b" * 32))[0], 200)
        self.assertEqual((await self.upload_banlist(1, 2, {}))[0], 409)
        status, headers, _ = await self.upload_banlist(1, 1, {"Steve": None})
        self.assertEqual(status, 409)
        self.assertNotIn(b"x-mcqq-banlist-conflict", headers)
        self.assertEqual((await self.upload_banlist(3, 1, {"Steve": None}, client_id="b" * 32))[0], 409)
        db_path = self.api.DB_PATH
        database.init_database(db_path)
        with patch.object(app_config, "load_config", return_value={**ENV, "MC_BANLIST_TOKEN": BANLIST_TOKEN}):
            self.api = importlib.reload(self.api)
        self.api.DB_PATH = db_path
        self.assertEqual((await self.request(BANLIST_PATH, [bearer(BANLIST_TOKEN)]))[2], {"generation": 2})
        self.assertEqual((await self.upload_banlist(2, 1, {"alex": None}, client_id="b" * 32))[0], 200)
        self.assertEqual((await self.upload_banlist(1, 2, {}))[0], 409)

    async def test_expired_upload_is_acknowledged_without_freezing_and_retains_its_original_digest(self):
        now = 1_850_000_000_000
        alex = "00000000-0000-4000-8000-000000000002"
        unnamed = "00000000-0000-4000-8000-000000000003"
        entries = {"Steve": now - 1}
        profiles = {
            alex: {"name": "Alex", "expires_at": now},
            unnamed: {"name": None, "expires_at": now},
        }
        with patch("banlist_sync.time") as clock:
            clock.time_ns.return_value = now * 1_000_000
            status, _, reply = await self.upload_banlist(1, 1, entries, profiles=profiles)
            self.assertEqual((status, reply), (200, {"generation": 1, "sequence": 1}))
            clock.time_ns.return_value += 10_000_000
            self.assertEqual((await self.upload_banlist(1, 1, entries, profiles=profiles))[0], 200)
            self.assertEqual((await self.upload_banlist(1, 1, {}, profiles={}))[0], 409)
        self.assertEqual((await self.request(SNAPSHOT_PATH, [bearer(API_TOKEN)]))[2], {
            "version": VERSION, "active_names": ["alex", "steve"],
        })
        with closing(sqlite3.connect(self.api.DB_PATH)) as conn:
            self.assertEqual(conn.execute("SELECT count(*) FROM server_bans").fetchone()[0], 0)

    async def test_uuid_only_bans_preserve_known_names_without_blocking_other_bans_and_pardons(self):
        steve = "00000000-0000-4000-8000-000000000001"
        unknown = "00000000-0000-4000-8000-000000000003"
        other = "00000000-0000-4000-8000-000000000004"
        entries = {"Alex": None, "Legacy": None}
        self.assertEqual((await self.upload_banlist(1, 1, entries))[0], 200)
        initial = {
            steve: {"name": "Steve", "expires_at": None},
        }
        self.assertEqual((await self.upload_banlist(1, 2, entries, profiles=initial))[0], 200)
        mixed = {other: {"name": "Other", "expires_at": None}}
        self.assertEqual((await self.upload_banlist(1, 3, {**entries, "STEVE": None}, profiles=mixed))[0], 200)
        database.init_database(self.api.DB_PATH)
        with closing(sqlite3.connect(self.api.DB_PATH)) as conn:
            saved = conn.execute(
                "SELECT mc_name_key, state FROM server_bans WHERE profile_uuid = ?", (steve,),
            ).fetchone()
            self.assertEqual(saved, ("steve", "KNOWN_PROFILE"))

        def states():
            with closing(sqlite3.connect(self.api.DB_PATH)) as conn:
                return dict(conn.execute("SELECT mc_name_key, state FROM server_bans WHERE profile_uuid IS NULL"))

        profiles = {
            steve: {"name": None, "expires_at": None},
            unknown: {"name": None, "expires_at": None},
            other: {"name": "Other", "expires_at": None},
        }
        replacement = "b" * 32
        self.assertEqual((await self.upload_banlist(2, 1, {}, replacement, profiles=profiles))[0], 200)
        self.assertEqual(states(), {
            "steve": "BANNED", "alex": "PENDING_MEMBERSHIP_CHECK", "other": "BANNED",
        })
        with (patch.object(binding_service, "DB_PATH", self.api.DB_PATH),
              patch.object(binding_service, "notify_auth_updated")):
            audit = binding_service.membership_audit_snapshot(GROUP_ID, only_pending=True)
            self.assertEqual(binding_service.reconcile_checked_bindings(GROUP_ID, audit, {"2"}, 2), 0)
        self.assertEqual((await self.request(SNAPSHOT_PATH, [bearer(API_TOKEN)]))[2]["active_names"], ["alex"])

        del profiles[steve]
        self.assertEqual((await self.upload_banlist(2, 2, {}, replacement, profiles=profiles))[0], 200)
        self.assertEqual(states(), {"steve": "PENDING_MEMBERSHIP_CHECK", "other": "BANNED"})

    async def test_known_uuid_is_enriched_in_place_without_an_unnamed_warning(self):
        known = "00000000-0000-4000-8000-000000000001"
        unknown = "00000000-0000-4000-8000-000000000002"
        alex = "00000000-0000-4000-8000-000000000003"

        def profile(uuid):
            with closing(sqlite3.connect(self.api.DB_PATH)) as conn:
                return conn.execute(
                    "SELECT id, mc_name_key, expires_at, state FROM server_bans WHERE profile_uuid = ?", (uuid,),
                ).fetchone()

        self.assertEqual((await self.upload_banlist(1, 1, {}, profiles={
            known: {"name": "Steve", "expires_at": None},
            alex: {"name": "Alex", "expires_at": None},
        }))[0], 200)
        row_id, name, expiration, state = profile(known)
        self.assertEqual((name, expiration, state), ("steve", None, "BANNED"))

        expires_at = database.MAX_AUTH_VERSION
        profiles = {known: {"name": None, "expires_at": expires_at}}
        with (patch.object(database, "DB_PATH", self.api.DB_PATH),
              patch.object(binding_service, "DB_PATH", self.api.DB_PATH),
              patch.object(binding_service, "notify_auth_updated")):
            self.assertEqual((await self.upload_banlist(1, 2, {"Alex": None}, profiles=profiles))[0], 200)
            self.assertEqual(profile(known), (row_id, "steve", expires_at, "BANNED"))
            self.assertEqual(binding_service.query_binding(GROUP_ID, "1"), ("frozen", "Steve"))
            self.assertEqual(runtime_status._binding_status(GROUP_ID)[2], 0)

            profiles[unknown] = {"name": None, "expires_at": None}
            self.assertEqual((await self.upload_banlist(1, 3, {}, profiles=profiles))[0], 200)
            self.assertEqual(profile(unknown)[1:], (None, None, "BANNED"))
            self.assertEqual(runtime_status._binding_status(GROUP_ID)[2], 1)
            audit = binding_service.membership_audit_snapshot(GROUP_ID, only_pending=True)
            self.assertEqual(set(audit.pending), {"2"})
            self.assertEqual(binding_service.reconcile_checked_bindings(GROUP_ID, audit, {"2"}, 2), 0)

        self.assertEqual((await self.request(SNAPSHOT_PATH, [bearer(API_TOKEN)]))[2]["active_names"], ["alex"])

    async def test_unmapped_offline_uuid_is_recorded_without_guessing_its_game_name(self):
        # UUID.nameUUIDFromBytes("OfflinePlayer:Steve".getBytes(UTF_8))
        steve = "5627dd98-e6be-3c21-b8a8-e92344183641"
        unknown = "00000000-0000-4000-8000-000000000003"
        self.assertEqual((await self.upload_banlist(1, 1, {"steve": None}))[0], 200)

        expiration = 1_850_000_000_000
        profiles = {
            steve: {"name": None, "expires_at": expiration},
            unknown: {"name": None, "expires_at": None},
        }
        with patch("banlist_sync.time") as clock:
            clock.time_ns.return_value = (expiration - 60_000) * 1_000_000
            self.assertEqual((await self.upload_banlist(1, 2, {}, profiles=profiles))[0], 200)
            self.assertEqual((await self.upload_banlist(1, 2, {}, profiles=profiles))[0], 200)
        with (patch.object(binding_service, "DB_PATH", self.api.DB_PATH),
              patch.object(binding_service, "notify_auth_updated")):
            self.assertEqual(binding_service.query_binding(GROUP_ID, "1"), ("pending", "Steve"))
            audit = binding_service.membership_audit_snapshot(GROUP_ID, only_pending=True)
            self.assertEqual(set(audit.pending), {"1"})
            self.assertEqual(binding_service.reconcile_checked_bindings(GROUP_ID, audit, {"1"}, 2), 0)
        self.assertEqual((await self.request(SNAPSHOT_PATH, [bearer(API_TOKEN)]))[2]["active_names"], ["alex", "steve"])

        def stored_profiles():
            with closing(sqlite3.connect(self.api.DB_PATH)) as conn:
                return {
                    uuid: (name, expiration, state)
                    for uuid, name, expiration, state in conn.execute(
                        "SELECT profile_uuid, mc_name_key, expires_at, state FROM server_bans WHERE profile_uuid IS NOT NULL"
                    )
                }

        database.init_database(self.api.DB_PATH)
        self.assertEqual(stored_profiles(), {steve: (None, expiration, "BANNED"), unknown: (None, None, "BANNED")})
        # An explicit server name associates the UUID and restores the usual ban behavior.
        self.assertEqual((await self.upload_banlist(1, 3, {}, profiles={
            steve: {"name": "Steve", "expires_at": None},
        }))[0], 200)
        self.assertEqual(stored_profiles(), {steve: ("steve", None, "BANNED")})
        self.assertEqual((await self.request(SNAPSHOT_PATH, [bearer(API_TOKEN)]))[2]["active_names"], ["alex"])

    async def test_multiple_profile_bans_share_one_binding_reservation_until_the_last_expires(self):
        first = "00000000-0000-4000-8000-000000000001"
        second = "00000000-0000-4000-8000-000000000002"
        expiration = 1_850_000_000_000

        def reservations():
            with closing(sqlite3.connect(self.api.DB_PATH)) as conn:
                return conn.execute(
                    "SELECT state, expires_at, qq_id FROM server_bans WHERE profile_uuid IS NULL",
                ).fetchall()

        with patch("banlist_sync.time") as clock:
            clock.time_ns.return_value = (expiration - 60_000) * 1_000_000
            self.assertEqual((await self.upload_banlist(1, 1, {}, profiles={
                first: {"name": "Steve", "expires_at": expiration - 10_000},
                second: {"name": "STEVE", "expires_at": expiration},
            }))[0], 200)
            self.assertEqual(reservations(), [("BANNED", expiration, "1")])

            remaining = {second: {"name": None, "expires_at": expiration}}
            self.assertEqual((await self.upload_banlist(1, 2, {}, profiles=remaining))[0], 200)
            self.assertEqual(reservations(), [("BANNED", expiration, "1")])

            clock.time_ns.return_value = expiration * 1_000_000
            self.assertEqual((await self.upload_banlist(1, 3, {}, profiles=remaining))[0], 200)
        self.assertEqual(reservations(), [("PENDING_MEMBERSHIP_CHECK", None, "1")])
        with closing(sqlite3.connect(self.api.DB_PATH)) as conn:
            self.assertEqual(conn.execute("SELECT COUNT(*) FROM server_bans WHERE profile_uuid IS NOT NULL").fetchone(), (0,))

    async def test_banlist_auth_validation_and_rollback_leave_cursor_unchanged(self):
        for header in ([bearer(API_TOKEN)], [bearer(NOTIFY_TOKEN)], [], [bearer(BANLIST_TOKEN)] * 2):
            self.assertEqual((await self.request(BANLIST_PATH, header, "PUT", b"{}"))[0], 401)
        with patch.object(self.api, "BANLIST_TOKEN", None):
            self.assertEqual((await self.upload_banlist(1, 1, {}))[0], 404)
        with patch.object(self.api, "MAX_BANLIST_BYTES", 4):
            self.assertEqual((await self.upload_banlist(1, 1, {}))[0], 413)
        for entries in ({"Bad Name": None}, {"Steve": True}, {"Steve": "42"}):
            self.assertEqual((await self.upload_banlist(1, 1, entries))[0], 422)
        self.assertEqual((await self.request(BANLIST_PATH, [bearer(BANLIST_TOKEN)], "PUT", b'{"entries":'))[0], 422)
        self.set_version(database.MAX_AUTH_VERSION)
        with self.assertLogs("mc_qq_auth", "ERROR"):
            self.assertEqual((await self.upload_banlist(1, 1, {"Steve": None}))[0], 503)
        self.assertEqual((await self.request(BANLIST_PATH, [bearer(BANLIST_TOKEN)]))[2], {"generation": 0})
        self.assertEqual((await self.request(SNAPSHOT_PATH, [bearer(API_TOKEN)]))[2]["active_names"], ["alex", "steve"])

    async def test_snapshot_excludes_frozen_and_pending_bindings_until_membership_is_verified(self):
        with (patch.object(binding_service, "DB_PATH", self.api.DB_PATH),
              patch.object(binding_service, "notify_auth_updated") as notify):
            binding_service.apply_ban_snapshot({"STEVE": None})
            status, _, frozen = await self.request(SNAPSHOT_PATH, [bearer(API_TOKEN)])
            self.assertEqual(status, 200)
            self.assertEqual(frozen["active_names"], ["alex"])
            self.assertGreater(frozen["version"], VERSION)

            binding_service.apply_ban_snapshot({})
            _, _, pending = await self.request(SNAPSHOT_PATH, [bearer(API_TOKEN)])
            self.assertEqual(pending, frozen)
            notify.assert_called_once_with()

            audit = binding_service.membership_audit_snapshot(GROUP_ID, only_pending=True)
            binding_service.reconcile_checked_bindings(GROUP_ID, audit, {"1"}, 2)
            _, _, restored = await self.request(SNAPSHOT_PATH, [bearer(API_TOKEN)])
            self.assertEqual(restored["active_names"], ["alex", "steve"])
            self.assertGreater(restored["version"], frozen["version"])
            self.assertEqual(notify.call_count, 2)

    async def test_http_rejects_invalid_bearer_input(self):
        cases = {
            "missing": [],
            "wrong": [bearer("test-only.invalid")],
            "empty": [b"Bearer "],
            "non-ascii": [b"Bearer abc\xff"],
            "control": [b"Bearer abc\x00"],
            "long": [b"Bearer " + b"x" * (app_config.MAX_TOKEN_LENGTH + 1)],
            "wrong-scheme": [b"Basic abc"],
            "bad-separator": [b"Bearer\tabc"],
            "extra-credential": [b"Bearer abc, Bearer def"],
            "duplicate-header": [bearer(API_TOKEN), bearer(API_TOKEN)],
            "invalid-padding": [b"Bearer ab=c"],
        }
        for name, header in cases.items():
            with self.subTest(case=name):
                status, headers, payload = await self.request(SNAPSHOT_PATH, header)
                self.assertEqual(status, 401)
                self.assertEqual(headers[b"www-authenticate"], b"Bearer")
                self.assertEqual(payload, {"detail": "Unauthorized"})

    async def test_notify_and_api_tokens_are_not_interchangeable(self):
        self.assertEqual((await self.request(SNAPSHOT_PATH, [bearer(NOTIFY_TOKEN)]))[0], 401)
        self.assertEqual((await self.request(NOTIFY_PATH, [bearer(API_TOKEN)], "POST"))[0], 401)
        self.assertEqual((await self.request(NOTIFY_PATH, [b"Bearer x\xff"], "POST"))[0], 401)
        status, _, body = await self.request(NOTIFY_PATH, [bearer(NOTIFY_TOKEN)], "POST")
        self.assertEqual(status, 200)
        self.assertEqual(body, {"success": True, "version": VERSION, "delivered": 0})

    async def test_websocket_denies_before_accept_and_registration(self):
        for name, header in (
            ("wrong", bearer("test-only.invalid")),
            ("non-ascii", b"Bearer abc\xff"),
            ("notify-token", bearer(NOTIFY_TOKEN)),
        ):
            async def receive():
                return {"type": "websocket.connect"}

            sent = []

            async def send(message):
                sent.append(message)

            with self.subTest(case=name):
                await asyncio.wait_for(
                    self.api.app(scope("websocket", WS_PATH, [header]), receive, send), timeout=2
                )
                self.assertEqual([message["type"] for message in sent], ["websocket.close"])
                self.assertFalse(self.api.connections)

    async def test_websocket_connects_and_broadcasts_existing_messages(self):
        status, _, health = await self.request("/internal/health")
        self.assertEqual((status, health), (200, {"status": "ok", "minecraft_connections": 0}))
        incoming = asyncio.Queue()
        outgoing = asyncio.Queue()
        await incoming.put({"type": "websocket.connect"})
        task = asyncio.create_task(
            self.api.app(
                scope("websocket", WS_PATH, [bearer(API_TOKEN)]), incoming.get, outgoing.put
            )
        )
        try:
            accepted = await asyncio.wait_for(outgoing.get(), timeout=2)
            self.assertEqual(accepted["type"], "websocket.accept")
            connected = await asyncio.wait_for(outgoing.get(), timeout=2)
            self.assertEqual(json.loads(connected["text"]), {
                "type": "connected", "message": "Minecraft authentication connected",
            })
            status, _, health = await self.request("/internal/health")
            self.assertEqual((status, health), (200, {"status": "ok", "minecraft_connections": 1}))
            status, _, notify = await self.request(NOTIFY_PATH, [bearer(NOTIFY_TOKEN)], "POST")
            self.assertEqual(status, 200)
            self.assertEqual(notify, {"success": True, "version": VERSION, "delivered": 1})
            updated = await asyncio.wait_for(outgoing.get(), timeout=2)
            self.assertEqual(json.loads(updated["text"]), {"type": "auth_updated", "version": VERSION})
        finally:
            await incoming.put({"type": "websocket.disconnect", "code": 1000})
            await asyncio.wait_for(task, timeout=2)
        self.assertFalse(self.api.connections)
        self.assertEqual((await self.request("/internal/health"))[2]["minecraft_connections"], 0)

    @asynccontextmanager
    async def websocket(self, intercept=None):
        incoming = asyncio.Queue()
        outgoing = asyncio.Queue()
        incoming.put_nowait({"type": "websocket.connect"})

        async def send(message):
            if intercept is not None:
                await intercept(message)
            outgoing.put_nowait(message)

        task = asyncio.create_task(self.api.app(
            scope("websocket", WS_PATH, [bearer(API_TOKEN)]), incoming.get, send,
        ))
        try:
            self.assertEqual((await asyncio.wait_for(outgoing.get(), 2))["type"], "websocket.accept")
            self.assertEqual(json.loads((await asyncio.wait_for(outgoing.get(), 2))["text"])["type"], "connected")
            yield outgoing, task
        finally:
            incoming.put_nowait({"type": "websocket.disconnect", "code": 1000})
            await asyncio.wait_for(task, 2)

    def set_version(self, version):
        with closing(sqlite3.connect(self.api.DB_PATH)) as conn, conn:
            conn.execute("UPDATE auth_state SET version = ?", (version,))

    async def test_failed_send_closes_only_the_failed_websocket(self):
        async def broken_send(message):
            if message["type"] == "websocket.send" and json.loads(message["text"])["type"] == "auth_updated":
                raise RuntimeError(API_TOKEN)

        async with self.websocket(broken_send) as (failed, failed_task), self.websocket() as (healthy, _):
            with self.assertLogs("mc_qq_auth", "WARNING") as logs:
                status, _, body = await self.request(NOTIFY_PATH, [bearer(NOTIFY_TOKEN)], "POST")
                self.assertEqual(status, 200)
                self.assertEqual(body, {"success": True, "version": VERSION, "delivered": 1})
                self.assertEqual(await asyncio.wait_for(failed.get(), 2), {
                    "type": "websocket.close", "code": 1011, "reason": "",
                })
            self.assertNotIn(API_TOKEN, "\n".join(logs.output))
            await asyncio.wait_for(failed_task, 2)
            self.assertEqual(json.loads((await asyncio.wait_for(healthy.get(), 2))["text"]), {
                "type": "auth_updated", "version": VERSION,
            })
            self.assertEqual(len(self.api.connections), 1)
            self.set_version(VERSION + 1)
            self.assertEqual(await self.api.broadcast_latest(), (VERSION + 1, 1))
            self.assertEqual(json.loads((await asyncio.wait_for(healthy.get(), 2))["text"])["version"], VERSION + 1)
        self.assertFalse(self.api.connections)

    async def test_blocked_send_and_close_do_not_hold_up_healthy_clients(self):
        sending = asyncio.Event()
        closing_socket = asyncio.Event()

        async def stalled_io(message):
            if message["type"] == "websocket.send" and json.loads(message["text"])["type"] == "auth_updated":
                sending.set()
                await asyncio.Future()
            if message["type"] == "websocket.close":
                closing_socket.set()
                await asyncio.Future()

        with patch.object(self.api, "WS_SEND_TIMEOUT", 0.15), patch.object(self.api, "WS_CLOSE_TIMEOUT", 0.3):
            async with self.websocket(stalled_io) as (_, failed_task), self.websocket() as (healthy, _):
                broadcast = asyncio.create_task(self.api.broadcast_latest())
                await asyncio.wait_for(sending.wait(), 2)
                update = await asyncio.wait_for(healthy.get(), 2)
                self.assertEqual(json.loads(update["text"])["version"], VERSION)
                self.assertFalse(broadcast.done())
                await asyncio.wait_for(closing_socket.wait(), 2)
                self.assertEqual(await asyncio.wait_for(broadcast, 2), (VERSION, 1))
                self.set_version(VERSION + 1)
                self.assertEqual(await self.api.broadcast_latest(), (VERSION + 1, 1))
                await asyncio.wait_for(failed_task, 2)
        self.assertFalse(self.api.connections)

    async def test_failed_close_ends_real_uvicorn_socket_and_reconnect_reads_latest_snapshot(self):
        inject_failure = True
        close_attempted = asyncio.Event()

        async def app_with_transport_failure(scope, receive, send):
            nonlocal inject_failure
            if scope["type"] != "websocket" or not inject_failure:
                await self.api.app(scope, receive, send)
                return
            inject_failure = False

            async def send_with_failure(message):
                if message["type"] == "websocket.close":
                    close_attempted.set()
                    raise OSError("synthetic close failure")
                if message["type"] == "websocket.send" and json.loads(message["text"])["type"] == "auth_updated":
                    raise RuntimeError("synthetic send failure")
                await send(message)

            await self.api.app(scope, receive, send_with_failure)

        with closing(socket.socket()) as listener:
            listener.bind(("127.0.0.1", 0))
            uri = f"ws://127.0.0.1:{listener.getsockname()[1]}{WS_PATH}"
            server = uvicorn.Server(uvicorn.Config(
                app_with_transport_failure, lifespan="off", log_level="critical", access_log=False,
            ))
            server_task = asyncio.create_task(server.serve(sockets=[listener]))
            try:
                async with asyncio.timeout(5):
                    while not server.started:
                        await asyncio.sleep(0.01)
                headers = {"Authorization": bearer(API_TOKEN).decode("ascii")}
                async with connect(uri, additional_headers=headers, proxy=None) as client:
                    self.assertEqual(json.loads(await asyncio.wait_for(client.recv(), 2))["type"], "connected")
                    with patch.object(binding_service, "DB_PATH", self.api.DB_PATH), patch.object(
                        binding_service, "notify_auth_updated", return_value=False
                    ):
                        self.assertEqual(binding_service.unbind_player(GROUP_ID, "1"), "Steve")
                    version, delivered = await self.api.broadcast_latest()
                    self.assertGreater(version, VERSION)
                    self.assertEqual(delivered, 0)
                    await asyncio.wait_for(client.wait_closed(), 2)
                    self.assertTrue(close_attempted.is_set())
                self.assertFalse(self.api.connections)
                async with connect(uri, additional_headers=headers, proxy=None) as client:
                    self.assertEqual(json.loads(await asyncio.wait_for(client.recv(), 2))["type"], "connected")
                    status, _, snapshot = await self.request(SNAPSHOT_PATH, [bearer(API_TOKEN)])
                    self.assertEqual(status, 200)
                    self.assertEqual(snapshot, {"version": version, "active_names": ["alex"]})
            finally:
                server.should_exit = True
                await asyncio.wait_for(server_task, 5)

    async def test_failed_notify_recovers_via_api_reconciliation(self):
        async with self.websocket() as (outgoing, _):
            self.assertEqual(await self.api.broadcast_latest(), (VERSION, 1))
            await asyncio.wait_for(outgoing.get(), 2)
            with patch.object(binding_service, "DB_PATH", self.api.DB_PATH), patch.object(
                notify_client, "load_config", return_value=ENV
            ), patch.object(notify_client.opener, "open", side_effect=URLError(NOTIFY_TOKEN)), self.assertLogs(
                "mc_qq_auth", "WARNING"
            ) as logs:
                self.assertEqual(binding_service.unbind_player(GROUP_ID, "1"), "Steve")
                self.assertIsNone(binding_service.get_binding(GROUP_ID, "1"))
            self.assertNotIn(NOTIFY_TOKEN, "\n".join(logs.output))
            self.assertTrue(outgoing.empty())
            with patch.object(self.api, "RECONCILE_INTERVAL", 0.01):
                await self.api.start_reconciliation()
                try:
                    update = json.loads((await asyncio.wait_for(outgoing.get(), 2))["text"])
                finally:
                    await self.api.stop_reconciliation()
            self.assertEqual(update["type"], "auth_updated")
            self.assertGreater(update["version"], VERSION)
            status, _, snapshot = await self.request(SNAPSHOT_PATH, [bearer(API_TOKEN)])
            self.assertEqual(status, 200)
            self.assertEqual(snapshot, {"version": update["version"], "active_names": ["alex"]})

    async def test_concurrent_duplicate_and_older_versions_are_not_rebroadcast(self):
        async with self.websocket() as (outgoing, _):
            results = await asyncio.gather(self.api.broadcast_latest(), self.api.broadcast_latest())
            self.assertCountEqual(results, [(VERSION, 1), (VERSION, 0)])
            update = await asyncio.wait_for(outgoing.get(), 2)
            self.assertEqual(json.loads(update["text"])["version"], VERSION)
            self.assertTrue(outgoing.empty())

            self.set_version(VERSION - 1)
            with self.assertLogs("mc_qq_auth", "ERROR"):
                self.assertEqual((await self.request(NOTIFY_PATH, [bearer(NOTIFY_TOKEN)], "POST"))[0], 503)
            self.assertEqual(self.api.last_broadcast_version, VERSION)
            self.assertTrue(outgoing.empty())

            self.set_version(VERSION + 1)
            self.assertEqual(await self.api.broadcast_latest(), (VERSION + 1, 1))
            self.assertEqual(json.loads((await asyncio.wait_for(outgoing.get(), 2))["text"])["version"], VERSION + 1)


class ConfigTests(unittest.TestCase):
    def setUp(self):
        temp = tempfile.TemporaryDirectory()
        self.addCleanup(temp.cleanup)
        self.root = Path(temp.name)
        self.env_file = self.root / ".env"
        self.env_file.write_text(
            f"MC_API_TOKEN={API_TOKEN}\nMC_NOTIFY_TOKEN={NOTIFY_TOKEN}\nTARGET_GROUP_ID=00{GROUP_ID}\n"
            "ONEBOT_V11_ACCESS_TOKEN=test-only.file-onebot\n",
            encoding="utf-8",
        )
        path_patch = patch.object(app_config, "ENV_FILE", self.env_file)
        path_patch.start()
        self.addCleanup(path_patch.stop)

    def test_dotenv_and_explicit_environment_override(self):
        with patch.dict(os.environ, {}, clear=True):
            config = app_config.load_config()
            self.assertEqual(app_config.require_token(config, "MC_API_TOKEN"), API_TOKEN)
            self.assertEqual(app_config.target_group_id(config), int(GROUP_ID))
            self.assertEqual(app_config.notify_url(config), app_config.DEFAULT_NOTIFY_URL)
            self.assertNotIn("MC_API_TOKEN", os.environ)

        with patch.dict(os.environ, {
            "MC_API_TOKEN": "test-only.override",
            "MC_NOTIFY_TOKEN": "",
            "ONEBOT_ACCESS_TOKEN": "test-only.environment-onebot",
        }, clear=True):
            config = app_config.load_config()
        self.assertEqual(app_config.require_token(config, "MC_API_TOKEN"), "test-only.override")
        self.assertEqual(config["ONEBOT_ACCESS_TOKEN"], "test-only.environment-onebot")
        with self.assertRaisesRegex(app_config.ConfigError, "MC_NOTIFY_TOKEN"):
            app_config.require_token(config, "MC_NOTIFY_TOKEN")

    def test_invalid_settings_report_keys_without_values(self):
        for token in ("tést-only", "test-only\nsecret", "REPLACE_WITH_MC_API_TOKEN"):
            with self.subTest(token_format=type(token).__name__):
                with self.assertRaisesRegex(app_config.ConfigError, "MC_API_TOKEN") as error:
                    app_config.require_token({"MC_API_TOKEN": token}, "MC_API_TOKEN")
                self.assertNotIn(token, str(error.exception))
        for group in ({}, {"TARGET_GROUP_ID": "0"}, {"TARGET_GROUP_ID": "１２３"}):
            with self.assertRaisesRegex(app_config.ConfigError, "TARGET_GROUP_ID"):
                app_config.target_group_id(group)
        with self.assertRaisesRegex(app_config.ConfigError, "ONEBOT_WS_URLS"):
            app_config.onebot_ws_urls({"ONEBOT_WS_URLS": "not-json"})
        with self.assertRaisesRegex(app_config.ConfigError, "MC_NOTIFY_URL") as error:
            app_config.notify_url({"MC_NOTIFY_URL": "http://user:test-only.password@127.0.0.1/"})
        self.assertNotIn("test-only.password", str(error.exception))

    def test_api_startup_rejects_missing_or_shared_credentials(self):
        cases = (
            ({"MC_API_TOKEN": ""}, "MC_API_TOKEN"),
            ({"MC_NOTIFY_TOKEN": ""}, "MC_NOTIFY_TOKEN"),
            ({"MC_NOTIFY_TOKEN": API_TOKEN}, "MC_API_TOKEN.*MC_NOTIFY_TOKEN"),
        )
        for override, keys in cases:
            with self.subTest(keys=keys), patch.dict(os.environ, override, clear=True):
                with self.assertRaisesRegex(app_config.ConfigError, keys) as error:
                    importlib.import_module("mc_api")
                self.assertNotIn(API_TOKEN, str(error.exception))

    def test_notify_url_override_and_direct_request(self):
        url = "http://127.0.0.1:19002/custom/notify"
        with patch.dict(os.environ, {"MC_NOTIFY_URL": url}, clear=True), patch.object(
            notify_client.opener, "open"
        ) as send:
            response = send.return_value.__enter__.return_value
            response.status = 200
            response.read.return_value = b'{"success":true,"version":1,"delivered":0}'
            self.assertTrue(notify_client.notify_auth_updated())
        request = send.call_args.args[0]
        self.assertEqual(request.full_url, url)
        self.assertEqual(request.get_method(), "POST")
        self.assertEqual(request.get_header("Authorization"), f"Bearer {NOTIFY_TOKEN}")
        self.assertEqual(send.call_args.kwargs, {"timeout": 3})

    def test_notify_requires_positive_acknowledgement_without_logging_response(self):
        with patch.object(notify_client.opener, "open") as send:
            response = send.return_value.__enter__.return_value
            for status, body in (
                (200, b'{"success":false}'),
                (200, b'{"success":true}'),
                (200, NOTIFY_TOKEN.encode("ascii")),
                (200, b"x" * 4097),
                (503, b'{"success":true}'),
            ):
                response.status = status
                response.read.return_value = body
                with self.subTest(status=status, length=len(body)), self.assertLogs("mc_qq_auth", "WARNING") as logs:
                    self.assertFalse(notify_client.notify_auth_updated())
                    self.assertNotIn(NOTIFY_TOKEN, "\n".join(logs.output))

    def test_network_failure_keeps_committed_binding_and_hides_error_values(self):
        path = self.root / "auth.db"
        database.init_database(path)
        with patch.dict(os.environ, {}, clear=True), patch.object(
            binding_service, "DB_PATH", path
        ), patch.object(
            notify_client.opener, "open", side_effect=URLError(NOTIFY_TOKEN)
        ), self.assertLogs("mc_qq_auth", level="WARNING") as logs:
            self.assertEqual(binding_service.bind_player(GROUP_ID, "qq", "MiXeD"), ("success", "MiXeD"))
            self.assertEqual(binding_service.get_binding(GROUP_ID, "qq"), "MiXeD")
        self.assertNotIn(NOTIFY_TOKEN, "\n".join(logs.output))
        with closing(sqlite3.connect(path)) as conn:
            self.assertGreater(conn.execute("SELECT version FROM auth_state WHERE id = 1").fetchone()[0], 0)

    def test_bot_and_api_start_from_unrelated_working_directory(self):
        project = self.root / "source"
        (project / "plugins").mkdir(parents=True)
        for rel in (
            "app_config.py", "banlist_sync.py", "bot.py", "binding_service.py", "database.py", "mc_api.py",
            "notify_client.py", "qq_avatar.py", "qq_group.py", "runtime_status.py", "status_image.py",
            "status_card.svg", "message_config.py", "sudo_api.py", "sudo_config.py",
            "plugins/qq_bindings.py", "plugins/qq_events.py", "plugins/status_commands.py", "plugins/qq_sudo.py",
        ):
            shutil.copyfile(BOT_DIR / rel, project / rel)
        onebot_token = "76543210123456"
        (project / ".env").write_text(
            self.env_file.read_text(encoding="utf-8")
            + 'DRIVER=~websockets\nLOG_LEVEL=TRACE\nONEBOT_WS_URLS=["ws://127.0.0.1:13001/"]\n',
            encoding="utf-8",
        )
        decoy = self.root / "elsewhere"
        decoy.mkdir()
        (decoy / ".env").write_text("TARGET_GROUP_ID=invalid\nDRIVER=invalid\n", encoding="utf-8")
        (project / "messages.yml").write_text("commands: {help: {message: configured}}", encoding="utf-8")
        (decoy / "messages.yml").write_text("commands: {help: {message: wrong directory}}", encoding="utf-8")
        code = """
import os
import sys
from pathlib import Path
sys.path.insert(0, sys.argv[1])
before = Path.cwd()
import bot
import mc_api
import message_config
import nonebot
from nonebot.adapters.onebot.v11 import Adapter
assert Path.cwd() == before
assert message_config.MESSAGES.text("commands.help.message") == "configured"
message_config.MESSAGE_FILE.unlink()
assert message_config.MESSAGES.text("commands.help.message") == "configured"
assert nonebot.get_driver().config.target_group_id == 98765
assert mc_api.TARGET_GROUP_ID == "98765"
adapter = nonebot.get_adapter(Adapter)
assert adapter.onebot_config.onebot_access_token == os.environ["ONEBOT_ACCESS_TOKEN"]
assert {plugin.module_name for plugin in nonebot.get_loaded_plugins()} == {
    "plugins.qq_bindings", "plugins.qq_events", "plugins.status_commands", "plugins.qq_sudo",
}
assert mc_api.sudo_settings is None
assert mc_api.BANLIST_TOKEN is None
"""
        env = {
            key: value for key, value in os.environ.items()
            if key.upper() not in {
                *ENV, "DRIVER", "LOG_LEVEL", "ENVIRONMENT", "ONEBOT_ACCESS_TOKEN",
                "ONEBOT_V11_ACCESS_TOKEN", "ONEBOT_WS_URLS", "ONEBOT_V11_WS_URLS",
                "MC_SUDO_QQ_IDS", "MC_SUDO_BOT_TOKEN", "MC_SUDO_PLUGIN_TOKEN",
                "MC_BANLIST_TOKEN",
            }
        }
        env.update(TARGET_GROUP_ID="98765", ONEBOT_ACCESS_TOKEN=onebot_token, PYTHONUTF8="1")
        result = subprocess.run(
            [sys.executable, "-B", "-c", code, str(project)],
            cwd=decoy, env=env, capture_output=True, text=True, encoding="utf-8", timeout=10,
        )
        for token in (API_TOKEN, NOTIFY_TOKEN, onebot_token, "test-only.file-onebot"):
            if token in result.stdout or token in result.stderr:
                self.fail("Bot startup exposed a synthetic credential")
        self.assertEqual(result.returncode, 0, result.stderr)
