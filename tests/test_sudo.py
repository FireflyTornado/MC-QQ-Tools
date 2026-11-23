import asyncio
import json
import sys
import time
import unittest
from contextlib import asynccontextmanager
from pathlib import Path
from unittest.mock import patch

from fastapi import FastAPI

sys.path.insert(0, str(Path(__file__).resolve().parents[1] / "bot"))

from sudo_api import sudo_router
from app_config import ConfigError
from sudo_config import (
    MAX_RESULT_BYTES, SUDO_FOLLOWUP_PATH, SUDO_SUBMIT_PATH, SUDO_WS_PATH,
    SudoSettings, is_single_command, read_sudo_output_settings, read_sudo_settings,
)


class SudoOutputSettingsTests(unittest.TestCase):
    def test_legacy_and_new_mod_token_keys(self):
        config = {"MC_SUDO_QQ_IDS": '["123"]', "MC_SUDO_BOT_TOKEN": "bot-token"}
        keys = ("MC_SUDO_MOD_TOKEN", "MC_SUDO_PLUGIN_TOKEN")
        for fields in ({keys[0]: "mod-token"}, {keys[1]: "mod-token"}, dict.fromkeys(keys, "mod-token")):
            self.assertEqual(read_sudo_settings(config | fields).mod_token, "mod-token")
        with self.assertRaisesRegex(ConfigError, "MC_SUDO_MOD_TOKEN.*MC_SUDO_PLUGIN_TOKEN") as error:
            read_sudo_settings(config | {keys[0]: "mod-token", keys[1]: "old-token"})
        self.assertNotIn("old-token", str(error.exception))
        for key, other in (keys, keys[::-1]):
            with self.subTest(key=key), self.assertRaisesRegex(ConfigError, key):
                read_sudo_settings(config | {key: f"REPLACE_WITH_{other}"})

    def test_bot_validates_mod_token_and_alias_when_configured(self):
        config = {"MC_SUDO_QQ_IDS": '["123"]', "MC_SUDO_BOT_TOKEN": "bot-token"}
        self.assertIsNone(read_sudo_settings(config, require_mod_token=False).mod_token)
        for key in ("MC_SUDO_MOD_TOKEN", "MC_SUDO_PLUGIN_TOKEN"):
            for value in ("", "not a bearer token"):
                with self.subTest(key=key, empty=not value), self.assertRaisesRegex(ConfigError, key):
                    read_sudo_settings({**config, key: value}, require_mod_token=False)
        with self.assertRaisesRegex(ConfigError, "MC_SUDO_MOD_TOKEN.*MC_SUDO_PLUGIN_TOKEN"):
            read_sudo_settings({**config, "MC_SUDO_MOD_TOKEN": "mod-token", "MC_SUDO_PLUGIN_TOKEN": "old-token"},
                               require_mod_token=False)

    def test_threshold_boundaries_and_config(self):
        defaults = read_sudo_output_settings({})
        self.assertFalse(defaults.use_file("a\r\n" * 10))
        self.assertTrue(defaults.use_file("a\r\n" * 11))
        self.assertFalse(defaults.use_file("字" * 1500))
        self.assertTrue(defaults.use_file("字" * 1501))
        custom = read_sudo_output_settings({
            "MC_SUDO_FILE_LINE_THRESHOLD": "2",
            "MC_SUDO_FILE_LINE_CHAR_THRESHOLD": "3",
            "MC_SUDO_TEXT_MAX_CHARS": "8",
        })
        self.assertFalse(custom.use_file("甲乙丙\n丁\n"))
        self.assertTrue(custom.use_file("甲乙丙丁\n"))
        self.assertTrue(custom.use_file("甲\n乙\n丙\n"))
        self.assertTrue(custom.use_file("aaa\r\nbbb\n"))
        with self.assertRaises(ConfigError):
            read_sudo_output_settings({"MC_SUDO_TEXT_MAX_CHARS": "0"})


class SudoApiTests(unittest.IsolatedAsyncioTestCase):
    def setUp(self):
        self.app = FastAPI()
        self.app.include_router(sudo_router(
            SudoSettings(frozenset({"101"}), "bot-token", "plugin-token"), "202",
            lambda headers, token: headers == ["Bearer " + token.decode("ascii")],
        ))

    async def submit(self, *, token="bot-token", timeout=2, request_id="a" * 32, **overrides):
        payload = {
            "id": request_id,
            "group_id": "202",
            "qq_id": "101",
            "command": "list",
            "deadline_ms": time.time_ns() // 1_000_000 + round(timeout * 1000),
            **overrides,
        }
        return await self.http("POST", SUDO_SUBMIT_PATH, token, json.dumps(payload).encode())

    async def http(self, method, path, token, body=b"", *, query=b""):
        sent = []

        async def send(message):
            sent.append(message)

        async def receive():
            return {"type": "http.request", "body": body}

        await self.app({
            "type": "http", "method": method, "scheme": "http", "path": path,
            "headers": [(b"authorization", f"Bearer {token}".encode())], "query_string": query,
        }, receive, send)
        return sent[0]["status"], json.loads(sent[-1]["body"])

    @asynccontextmanager
    async def connect(self, token="plugin-token", *, block_send=None):
        incoming = asyncio.Queue()
        outgoing = asyncio.Queue()
        incoming.put_nowait({"type": "websocket.connect"})

        async def receive():
            message = await incoming.get()
            if isinstance(message, asyncio.Event):
                # The handler has returned to receive after processing the preceding frame.
                message.set()
                message = await incoming.get()
            return message

        async def send(message):
            outgoing.put_nowait(message)
            if block_send is not None and message["type"] == "websocket.send":
                await block_send.wait()

        task = asyncio.create_task(self.app({
            "type": "websocket", "scheme": "ws", "path": SUDO_WS_PATH,
            "headers": [(b"authorization", f"Bearer {token}".encode())], "query_string": b"",
        }, receive, send))
        try:
            handshake = await asyncio.wait_for(outgoing.get(), 2)
            yield handshake["type"], incoming, outgoing
        finally:
            incoming.put_nowait({"type": "websocket.disconnect", "code": 1000})
            await asyncio.wait_for(task, 2)

    @staticmethod
    def result(incoming, request_id, text="在线玩家 0/20", **fields):
        incoming.put_nowait({"type": "websocket.receive", "text": json.dumps({
            "id": request_id, "status": "ok", "output": text, **fields,
        })})

    async def test_forwards_full_byte_budget_and_truncation_flag(self):
        output = "\x00" * MAX_RESULT_BYTES  # Worst-case JSON expansion of a UTF-8 byte.
        async with self.connect() as (_, incoming, outgoing):
            first = asyncio.create_task(self.submit())
            request = json.loads((await asyncio.wait_for(outgoing.get(), 2))["text"])
            self.result(incoming, request["id"], output, truncated=True)
            self.assertEqual(await first, (200, {"status": "ok", "output": output, "truncated": True}))

    async def test_permission_and_single_command_validation(self):
        self.assertEqual((await self.submit(qq_id="102"))[0], 403)
        self.assertEqual((await self.submit(group_id="303"))[0], 403)
        self.assertEqual((await self.submit(token="plugin-token"))[0], 401)
        self.assertEqual((await self.submit(command="list\nsay hello"))[0], 400)
        self.assertFalse(is_single_command("say first\u2028second"))
        self.assertEqual(await self.submit(), (200, {"status": "unavailable"}))
        async with self.connect("bot-token") as (handshake, _, _):
            self.assertEqual(handshake, "websocket.close")

    async def test_waits_for_matching_result_and_refuses_concurrent_work(self):
        async with self.connect() as (handshake, incoming, outgoing):
            self.assertEqual(handshake, "websocket.accept")
            async with self.connect() as (second_handshake, _, _):
                self.assertEqual(second_handshake, "websocket.close")
            first = asyncio.create_task(self.submit())
            request = json.loads((await asyncio.wait_for(outgoing.get(), 2))["text"])
            self.assertEqual(request["command"], "list")
            self.assertEqual(await self.submit(request_id="b" * 32), (200, {"status": "busy"}))
            self.result(incoming, "b" * 32, "wrong request output")
            processed = asyncio.Event()
            incoming.put_nowait(processed)
            await asyncio.wait_for(processed.wait(), 2)
            self.assertFalse(first.done())
            self.result(incoming, request["id"])
            self.assertEqual(await first, (200, {"status": "ok", "output": "在线玩家 0/20"}))

    async def test_scheduled_reply_releases_slot_and_keeps_authenticated_followup(self):
        async with self.connect() as (_, incoming, outgoing):
            scheduled = asyncio.create_task(self.submit(command="schedule function demo:notice 45s"))
            request = json.loads((await asyncio.wait_for(outgoing.get(), 2))["text"])
            self.result(incoming, request["id"], "已预约", status="scheduled")
            self.assertEqual(await scheduled, (200, {"status": "scheduled", "output": "已预约"}))
            path = f"{SUDO_FOLLOWUP_PATH}/{request['id']}"
            self.assertEqual((await self.http("GET", path, "plugin-token"))[0], 401)
            followup = asyncio.create_task(self.http("GET", path, "bot-token"))

            second = asyncio.create_task(self.submit(request_id="b" * 32))
            request2 = json.loads((await asyncio.wait_for(outgoing.get(), 2))["text"])
            self.result(incoming, request2["id"], "unrelated delayed", followup=True)
            self.result(incoming, request2["id"])
            self.assertEqual((await second)[1]["output"], "在线玩家 0/20")
            self.assertFalse(followup.done())
            self.result(incoming, request["id"], "完成", followup=True)
            self.assertEqual(await asyncio.wait_for(followup, 2), (200, {"status": "ok", "output": "完成"}))

    async def test_disconnect_finishes_waiting_scheduled_result(self):
        async with self.connect() as (_, incoming, outgoing):
            first = asyncio.create_task(self.submit())
            request = json.loads((await asyncio.wait_for(outgoing.get(), 2))["text"])
            self.result(incoming, request["id"], "已预约", status="scheduled")
            await first
            path = f"{SUDO_FOLLOWUP_PATH}/{request['id']}"
            followup = asyncio.create_task(self.http("GET", path, "bot-token"))
        self.assertEqual(await asyncio.wait_for(followup, 2), (200, {"status": "disconnected"}))

    async def test_stream_replays_ordered_frames_after_cursor_and_keeps_them_on_disconnect(self):
        async with self.connect() as (_, incoming, outgoing):
            first = asyncio.create_task(self.submit())
            request = json.loads((await asyncio.wait_for(outgoing.get(), 2))["text"])
            self.assertIs(request["stream_followup"], True)
            self.result(incoming, request["id"], "", wait_seconds=30)
            self.result(incoming, request["id"], "第一段\n", followup=True, sequence=1, final=False)
            self.result(incoming, request["id"], "第一段\n", followup=True, sequence=1, final=False)
            self.result(incoming, request["id"], "第二段\n", followup=True, sequence=2, final=False)
            self.assertEqual(await first, (200, {"status": "ok", "output": "", "wait_seconds": 30}))
            path = f"{SUDO_FOLLOWUP_PATH}/{request['id']}"
            initial = (200, {"status": "ok", "output": "第一段\n", "sequence": 1, "final": False})
            self.assertEqual(await self.http("GET", path, "bot-token", query=b"after=0"), initial)
            self.assertEqual(await self.http("GET", path, "bot-token", query=b"after=0"), initial)
        self.assertEqual(await self.http("GET", path, "bot-token", query=b"after=1"),
                         (200, {"status": "ok", "output": "第二段\n", "sequence": 2, "final": False}))
        self.assertEqual(await self.http("GET", path, "bot-token", query=b"after=2"),
                         (200, {"status": "disconnected", "sequence": 3, "final": True}))
        self.assertEqual(await self.http("GET", path, "bot-token", query=b"after=3"),
                         (200, {"status": "closed"}))

    async def test_stream_retains_the_initial_error_with_or_without_followup_capacity(self):
        async with self.connect() as (_, incoming, outgoing):
            first = asyncio.create_task(self.submit())
            request = json.loads((await asyncio.wait_for(outgoing.get(), 2))["text"])
            self.result(incoming, request["id"], "", status="error", wait_seconds=3600)
            self.result(incoming, request["id"], "后台任务完成", followup=True, sequence=1, final=True)
            self.assertEqual(await first, (200, {"status": "error", "output": "", "wait_seconds": 3600}))
            path = f"{SUDO_FOLLOWUP_PATH}/{request['id']}"
            self.assertEqual(await self.http("GET", path, "bot-token", query=b"after=0"),
                             (200, {"status": "ok", "output": "后台任务完成", "sequence": 1, "final": True}))

            with patch("sudo_api.MAX_FOLLOWUPS", 0):
                second = asyncio.create_task(self.submit(request_id="b" * 32))
                request = json.loads((await asyncio.wait_for(outgoing.get(), 2))["text"])
                self.result(incoming, request["id"], "命令失败", status="error", wait_seconds=3600)
                self.assertEqual(await second, (200, {"status": "error", "output": "命令失败"}))

    async def test_slow_stream_delivery_keeps_unconfirmed_frames_until_terminal_ack(self):
        # A group can take 18 seconds to upload any one of the 30 legal TXT frames.
        with patch("sudo_api.time", wraps=time) as clock, patch("sudo_api.FOLLOWUP_DELIVERY_RETENTION", 300):
            clock.monotonic.return_value = 100.0
            async with self.connect() as (_, incoming, outgoing):
                first = asyncio.create_task(self.submit())
                request = json.loads((await asyncio.wait_for(outgoing.get(), 2))["text"])
                self.result(incoming, request["id"], "", wait_seconds=3600)
                output = "x" * 1800
                for sequence in range(1, 31):
                    self.result(incoming, request["id"], output, followup=True,
                                sequence=sequence, final=sequence == 30)
                await first
                path = f"{SUDO_FOLLOWUP_PATH}/{request['id']}"

                for after in range(30):
                    clock.monotonic.return_value = 100.0 + after * 18
                    status, reply = await self.http("GET", path, "bot-token", query=f"after={after}".encode())
                    self.assertEqual(status, 200)
                    self.assertEqual(reply, {"status": "ok", "output": output,
                                             "sequence": after + 1, "final": after == 29})
                clock.monotonic.return_value = 640.0
                self.assertEqual(await self.http("GET", path, "bot-token", query=b"after=29"),
                                 (200, {"status": "ok", "output": output, "sequence": 30, "final": True}))
                self.assertEqual(await self.http("GET", path, "bot-token", query=b"after=30"),
                                 (200, {"status": "closed"}))
                self.assertEqual(await self.http("GET", path, "bot-token", query=b"after=0"),
                                 (200, {"status": "closed"}))

    async def test_followup_capacity_does_not_evict_an_unconfirmed_terminal_frame(self):
        with patch("sudo_api.MAX_FOLLOWUPS", 1):
            async with self.connect() as (_, incoming, outgoing):
                first = asyncio.create_task(self.submit())
                request = json.loads((await asyncio.wait_for(outgoing.get(), 2))["text"])
                self.result(incoming, request["id"], "", wait_seconds=30)
                self.result(incoming, request["id"], "完成", followup=True, sequence=1, final=True)
                await first
                path = f"{SUDO_FOLLOWUP_PATH}/{request['id']}"

                self.assertEqual((await self.http("GET", path, "bot-token", query=b"after=2"))[0], 400)
                second = asyncio.create_task(self.submit(request_id="b" * 32))
                request = json.loads((await asyncio.wait_for(outgoing.get(), 2))["text"])
                self.result(incoming, request["id"], "", wait_seconds=30)
                self.assertEqual(await second, (200, {"status": "partial", "output": ""}))
                self.assertEqual(await self.http("GET", path, "bot-token", query=b"after=0"),
                                 (200, {"status": "ok", "output": "完成", "sequence": 1, "final": True}))
                self.assertEqual(await self.http("GET", path, "bot-token", query=b"after=1"),
                                 (200, {"status": "closed"}))

                third = asyncio.create_task(self.submit(request_id="c" * 32))
                request = json.loads((await asyncio.wait_for(outgoing.get(), 2))["text"])
                self.result(incoming, request["id"], "", wait_seconds=30)
                self.assertEqual(await third, (200, {"status": "ok", "output": "", "wait_seconds": 30}))
                self.assertEqual(await self.http("GET", path, "bot-token", query=b"after=0"),
                                 (200, {"status": "expired"}))

    async def test_timeout_releases_slot_and_late_result_is_ignored(self):
        async with self.connect() as (_, incoming, outgoing):
            first = asyncio.create_task(self.submit(timeout=0.15))
            prior = json.loads((await asyncio.wait_for(outgoing.get(), 2))["text"])
            self.assertEqual(await first, (200, {"status": "timeout"}))
            next_request = asyncio.create_task(self.submit(request_id="b" * 32))
            current = json.loads((await asyncio.wait_for(outgoing.get(), 2))["text"])
            self.result(incoming, prior["id"], "old result")
            await asyncio.sleep(0)
            self.assertFalse(next_request.done())
            self.result(incoming, current["id"])
            self.assertEqual((await next_request)[1], {"status": "ok", "output": "在线玩家 0/20"})

    async def test_disconnect_releases_pending_command_even_if_send_is_blocked(self):
        async with self.connect(block_send=asyncio.Event()) as (_, _, outgoing):
            first = asyncio.create_task(self.submit(timeout=30))
            await asyncio.wait_for(outgoing.get(), 2)

        self.assertEqual(await asyncio.wait_for(first, 1), (200, {"status": "disconnected"}))
        self.assertEqual(await self.submit(), (200, {"status": "unavailable"}))
        async with self.connect() as (_, incoming, outgoing):
            next_request = asyncio.create_task(self.submit(request_id="b" * 32))
            request = json.loads((await asyncio.wait_for(outgoing.get(), 2))["text"])
            self.result(incoming, request["id"], "after reconnect")
        # The matching result was queued before the disconnect and must win.
        self.assertEqual(await next_request, (200, {"status": "ok", "output": "after reconnect"}))
