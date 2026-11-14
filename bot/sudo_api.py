import asyncio
import json
import logging
import re
import time
from collections.abc import Awaitable, Callable
from dataclasses import dataclass

from fastapi import APIRouter, Header, HTTPException, Request

from sudo_config import (
    MAX_RESULT_BYTES,
    SUDO_SUBMIT_PATH,
    SUDO_TIMEOUT,
    SudoSettings,
    is_single_command,
)

logger = logging.getLogger("uvicorn.error.mc_qq_auth.sudo")
PLUGIN_RESULTS = {"ok", "rejected", "error", "busy", "expired"}
MAX_MESSAGE_BYTES = 8192
SendMessage = Callable[[dict], Awaitable[bool]]


@dataclass
class PendingCommand:
    request_id: str
    send: SendMessage
    result: asyncio.Future


async def send_command(work: PendingCommand, command: str, deadline_ms: int) -> None:
    if not await work.send({
        "type": "sudo_command", "id": work.request_id, "command": command, "deadline_ms": deadline_ms,
    }):
        # An interrupted write can still have reached the game server.
        logger.warning("/sudo #%s 未能确认指令已发送", work.request_id)


class SudoService:
    def __init__(
        self, settings: SudoSettings | None, target_group: str,
        verify_bearer: Callable[[list[str] | None, bytes], bool],
    ):
        self.settings = settings
        self.target_group = target_group
        self.verify_bearer = verify_bearer
        self.plugins: dict[SendMessage, None] = {}
        self.pending: PendingCommand | None = None
        self.router = APIRouter()
        self.router.add_api_route(SUDO_SUBMIT_PATH, self.submit, methods=["POST"])

    async def submit(self, request: Request, authorization: list[str] | None = Header(default=None)):
        settings = self.settings
        if settings is None:
            raise HTTPException(404)
        if not self.verify_bearer(authorization, settings.bot_token.encode("ascii")):
            raise HTTPException(401)

        body = bytearray()
        async for chunk in request.stream():
            if len(body) + len(chunk) > MAX_MESSAGE_BYTES:
                raise HTTPException(413)
            body.extend(chunk)
        try:
            data = json.loads(body)
        except (ValueError, RecursionError):
            raise HTTPException(400) from None
        if not isinstance(data, dict):
            raise HTTPException(400)
        qq_id = data.get("qq_id")
        if data.get("group_id") != self.target_group or not isinstance(qq_id, str) or qq_id not in settings.qq_ids:
            raise HTTPException(403)
        command = data.get("command")
        request_id = data.get("id")
        deadline_ms = data.get("deadline_ms")
        if (
            not isinstance(request_id, str) or re.fullmatch(r"[0-9a-f]{32}", request_id) is None
            or not isinstance(command, str) or not is_single_command(command)
            or type(deadline_ms) is not int
        ):
            raise HTTPException(400)
        remaining = deadline_ms - time.time_ns() // 1_000_000
        if remaining <= 0:
            return {"status": "expired"}
        if remaining > SUDO_TIMEOUT * 1000:
            raise HTTPException(400)
        if self.pending is not None:
            return {"status": "busy"}
        send = next(iter(self.plugins), None)
        if send is None:
            return {"status": "unavailable"}

        work = PendingCommand(request_id, send, asyncio.get_running_loop().create_future())
        self.pending = work
        logger.info("/sudo #%s group=%s qq=%s command=%r", request_id, self.target_group, qq_id, command)
        status = "timeout"
        try:
            async with asyncio.timeout(remaining / 1000):
                async with asyncio.TaskGroup() as tasks:
                    sending = tasks.create_task(send_command(work, command, deadline_ms))
                    try:
                        result = await work.result
                    finally:
                        sending.cancel()
                status = result["status"]
                return result
        except TimeoutError:
            return {"status": status}
        finally:
            self.pending = None
            if not work.result.done():
                work.result.cancel()
            logger.info("/sudo #%s result=%s", request_id, status)

    def attach(self, send: SendMessage, credentials: list[str]) -> bool:
        if self.settings is None or not self.verify_bearer(credentials, self.settings.plugin_token.encode("ascii")):
            return False
        self.plugins[send] = None
        return True

    def detach(self, send: SendMessage) -> None:
        self.plugins.pop(send, None)
        pending = self.pending
        if pending is not None and pending.send == send and not pending.result.done():
            pending.result.set_result({"status": "disconnected"})

    def receive(self, send: SendMessage, result: dict) -> None:
        pending = self.pending
        if (
            pending is None or pending.send != send or pending.result.done()
            or result.get("id") != pending.request_id
        ):
            return
        status, output = result.get("status"), result.get("output")
        truncated = result.get("truncated", False)
        if (
            not isinstance(status, str) or status not in PLUGIN_RESULTS
            or not isinstance(output, str) or len(output) > MAX_RESULT_BYTES
            or any(0xD800 <= ord(char) <= 0xDFFF for char in output)
            or len(output.encode("utf-8")) > MAX_RESULT_BYTES or type(truncated) is not bool
        ):
            logger.warning("/sudo #%s 回执字段无效", pending.request_id)
            pending.result.set_result({"status": "error"})
            return
        reply = {"status": status, "output": output}
        if truncated:
            reply["truncated"] = True
        pending.result.set_result(reply)
