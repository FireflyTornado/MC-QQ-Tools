import asyncio
import json
import logging
import re
import time
from collections.abc import Callable
from dataclasses import dataclass

from fastapi import APIRouter, Header, HTTPException, Request, WebSocket, WebSocketDisconnect

from sudo_config import (
    MAX_RESULT_BYTES,
    MAX_RESULT_MESSAGE_BYTES,
    SUDO_FOLLOWUP_PATH,
    SUDO_FOLLOWUP_TIMEOUT,
    SUDO_SUBMIT_PATH,
    SUDO_TIMEOUT,
    SUDO_WS_PATH,
    SudoSettings,
    is_single_command,
)

logger = logging.getLogger("uvicorn.error.mc_qq_auth.sudo")
PLUGIN_RESULTS = {"ok", "rejected", "error", "busy", "expired", "partial", "scheduled"}
FOLLOWUP_RESULTS = {"ok", "error", "cancelled", "expired", "partial"}
MAX_MESSAGE_BYTES = 8192
SEND_TIMEOUT = 3
MAX_FOLLOWUPS = 128


@dataclass
class PendingCommand:
    request_id: str
    plugin: WebSocket
    result: asyncio.Future


@dataclass
class Followup:
    plugin: WebSocket
    result: asyncio.Future
    expires_at: float

    def complete(self, result: dict) -> None:
        if not self.result.done():
            self.expires_at = time.monotonic() + 300
            self.result.set_result(result)


async def send_command(work: PendingCommand, command: str, deadline_ms: int) -> None:
    try:
        async with asyncio.timeout(SEND_TIMEOUT):
            await work.plugin.send_json({"id": work.request_id, "command": command, "deadline_ms": deadline_ms})
    except WebSocketDisconnect:
        if not work.result.done():
            work.result.set_result({"status": "disconnected"})
    except (TimeoutError, OSError, RuntimeError):
        # An interrupted write can still have reached the game server.
        logger.warning("/sudo #%s 未能确认指令已发送", work.request_id)


def sudo_router(
    settings: SudoSettings | None,
    target_group: str,
    verify_bearer: Callable[[list[str] | None, bytes], bool],
) -> APIRouter:
    router = APIRouter()
    plugin: WebSocket | None = None
    ready = False
    pending: PendingCommand | None = None
    followups: dict[str, Followup] = {}

    def prune_followups() -> None:
        now = time.monotonic()
        for request_id in [key for key, item in followups.items() if item.expires_at <= now]:
            followups.pop(request_id).complete({"status": "expired"})

    @router.get(SUDO_FOLLOWUP_PATH + "/{request_id}")
    async def followup(request_id: str, authorization: list[str] | None = Header(default=None)):
        if settings is None:
            raise HTTPException(404)
        if not verify_bearer(authorization, settings.bot_token.encode("ascii")):
            raise HTTPException(401)
        prune_followups()
        work = followups.get(request_id)
        if work is None:
            return {"status": "expired"}
        try:
            async with asyncio.timeout(15):
                return await asyncio.shield(work.result)
        except TimeoutError:
            return {"status": "waiting"}

    @router.post(SUDO_SUBMIT_PATH)
    async def submit(request: Request, authorization: list[str] | None = Header(default=None)):
        nonlocal pending
        if settings is None:
            raise HTTPException(404)
        if not verify_bearer(authorization, settings.bot_token.encode("ascii")):
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
        if data.get("group_id") != target_group or not isinstance(qq_id, str) or qq_id not in settings.qq_ids:
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
        if pending is not None:
            return {"status": "busy"}
        prune_followups()
        if request_id in followups:
            return {"status": "rejected"}
        if plugin is None or not ready:
            return {"status": "unavailable"}

        work = PendingCommand(request_id, plugin, asyncio.get_running_loop().create_future())
        pending = work
        logger.info("/sudo #%s group=%s qq=%s command=%r", request_id, target_group, qq_id, command)
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
            pending = None
            if not work.result.done():
                work.result.cancel()
            logger.info("/sudo #%s result=%s", request_id, status)

    @router.websocket(SUDO_WS_PATH)
    async def results(websocket: WebSocket):
        nonlocal plugin, ready
        if (
            settings is None or plugin is not None
            or not verify_bearer(websocket.headers.getlist("authorization"), settings.plugin_token.encode("ascii"))
        ):
            await websocket.close(code=1008)
            return

        plugin = websocket
        try:
            await websocket.accept()
            ready = True
            while True:
                message = await websocket.receive()
                if message["type"] == "websocket.disconnect":
                    break
                raw = message.get("text")
                if raw is None or len(raw.encode("utf-8")) > MAX_RESULT_MESSAGE_BYTES:
                    await websocket.close(code=1003 if raw is None else 1009)
                    break
                try:
                    result = json.loads(raw)
                except (ValueError, RecursionError):
                    await websocket.close(code=1007)
                    break
                if not isinstance(result, dict):
                    await websocket.close(code=1007)
                    break
                status, output = result.get("status"), result.get("output")
                truncated = result.get("truncated", False)
                is_followup = result.get("followup", False)
                request_id = result.get("id")
                if (
                    type(is_followup) is not bool or not isinstance(request_id, str)
                    or not isinstance(status, str)
                    or status not in (FOLLOWUP_RESULTS if is_followup else PLUGIN_RESULTS)
                    or not isinstance(output, str) or len(output) > MAX_RESULT_BYTES
                    or any(0xD800 <= ord(char) <= 0xDFFF for char in output)
                    or len(output.encode("utf-8")) > MAX_RESULT_BYTES or type(truncated) is not bool
                ):
                    await websocket.close(code=1007)
                    break
                reply = {"status": status, "output": output}
                if truncated:
                    reply["truncated"] = True
                if is_followup:
                    prune_followups()
                    work = followups.get(request_id)
                    if work is not None and work.plugin is websocket:
                        work.complete(reply)
                    continue
                if (
                    pending is not None and pending.plugin is websocket and not pending.result.done()
                    and request_id == pending.request_id
                ):
                    if status == "scheduled":
                        prune_followups()
                        if len(followups) >= MAX_FOLLOWUPS:
                            completed = next((key for key, item in followups.items() if item.result.done()), None)
                            if completed is not None:
                                del followups[completed]
                        if len(followups) < MAX_FOLLOWUPS:
                            followups[request_id] = Followup(
                                websocket, asyncio.get_running_loop().create_future(),
                                time.monotonic() + SUDO_FOLLOWUP_TIMEOUT + SUDO_TIMEOUT,
                            )
                        else:
                            reply["status"] = "partial"
                    pending.result.set_result(reply)
        finally:
            ready = False
            plugin = None
            if pending is not None and pending.plugin is websocket and not pending.result.done():
                pending.result.set_result({"status": "disconnected"})
            for work in followups.values():
                if work.plugin is websocket:
                    work.complete({"status": "disconnected"})

    return router
