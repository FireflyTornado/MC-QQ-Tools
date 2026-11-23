import asyncio
import json
import logging
import re
import time
from collections.abc import Callable
from dataclasses import dataclass, field

from fastapi import APIRouter, Header, HTTPException, Request, WebSocket, WebSocketDisconnect

from sudo_config import (
    MAX_FOLLOWUP_FRAMES,
    MAX_RESULT_BYTES,
    MAX_RESULT_MESSAGE_BYTES,
    SUDO_DIRECT_REPLY_TIMEOUT,
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
FOLLOWUP_DELIVERY_RETENTION = SUDO_FOLLOWUP_TIMEOUT + 2 * SUDO_TIMEOUT
FOLLOWUP_CONFIRMATION_RETENTION = 300


@dataclass
class PendingCommand:
    request_id: str
    plugin: WebSocket
    result: asyncio.Future


@dataclass
class Followup:
    plugin: WebSocket
    expires_at: float
    streaming: bool
    frames: list[dict] = field(default_factory=list)
    changed: asyncio.Event = field(default_factory=asyncio.Event)
    finished: bool = False
    output_bytes: int = 0
    delivered_sequence: int = 0

    def publish(self, result: dict, *, final: bool = True, sequence: int | None = None) -> bool:
        if self.finished or (sequence is not None and sequence <= len(self.frames)):
            return True
        if sequence != (len(self.frames) + 1 if self.streaming else None):
            return False
        total_bytes = self.output_bytes + len(result.get("output", "").encode("utf-8"))
        if total_bytes > MAX_RESULT_BYTES or (not final and len(self.frames) >= MAX_FOLLOWUP_FRAMES - 1):
            return False
        self.output_bytes = total_bytes
        self.frames.append(result)
        if final:
            self.finished = True
            self.expires_at = time.monotonic() + FOLLOWUP_DELIVERY_RETENTION
        self.changed.set()
        return True

    def complete(self, result: dict) -> None:
        self.publish(result, sequence=len(self.frames) + 1 if self.streaming else None)

    def confirm_delivery(self, after: int | None) -> None:
        if not self.finished or self.delivered_sequence:
            return
        if after == len(self.frames):
            self.delivered_sequence = len(self.frames)
            self.frames.clear()
            self.output_bytes = 0
            self.expires_at = time.monotonic() + FOLLOWUP_CONFIRMATION_RETENTION
        else:
            # A poll may fetch a frame that still needs a lengthy QQ delivery attempt.
            self.expires_at = time.monotonic() + FOLLOWUP_DELIVERY_RETENTION

    def read(self, after: int | None) -> dict | None:
        if self.delivered_sequence:
            return {"status": "closed"}
        if after is not None and after < len(self.frames):
            sequence = after + 1
            return {**self.frames[after], "sequence": sequence, "final": self.finished and sequence == len(self.frames)}
        if not self.finished:
            return None
        if after is not None:
            return {"status": "closed"}
        result = self.frames[-1].copy()
        if len(self.frames) > 1:
            result["output"] = "".join(frame.get("output", "") for frame in self.frames)
            if any(frame.get("truncated", False) for frame in self.frames):
                result["truncated"] = True
        return result


async def send_command(work: PendingCommand, command: str, deadline_ms: int) -> None:
    try:
        async with asyncio.timeout(SEND_TIMEOUT):
            await work.plugin.send_json({"id": work.request_id, "command": command, "deadline_ms": deadline_ms,
                                         "stream_followup": True})
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
        for request_id, work in list(followups.items()):
            if work.expires_at <= now:
                if work.finished:
                    del followups[request_id]
                else:
                    work.complete({"status": "expired"})

    @router.get(SUDO_FOLLOWUP_PATH + "/{request_id}")
    async def followup(request_id: str, after: int | None = None, authorization: list[str] | None = Header(default=None)):
        if settings is None:
            raise HTTPException(404)
        if not verify_bearer(authorization, settings.bot_token.encode("ascii")):
            raise HTTPException(401)
        if after is not None and not 0 <= after <= MAX_FOLLOWUP_FRAMES:
            raise HTTPException(400)
        prune_followups()
        work = followups.get(request_id)
        if work is None:
            return {"status": "expired"}
        if after is not None and after > (work.delivered_sequence or len(work.frames)):
            raise HTTPException(400)
        try:
            async with asyncio.timeout(15):
                while (result := work.read(after)) is None:
                    work.changed.clear()
                    await work.changed.wait()
                work.confirm_delivery(after)
                return result
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
                wait_seconds = result.get("wait_seconds")
                sequence = result.get("sequence")
                final = result.get("final", True)
                request_id = result.get("id")
                if (
                    type(is_followup) is not bool or not isinstance(request_id, str)
                    or not isinstance(status, str)
                    or status not in (FOLLOWUP_RESULTS if is_followup else PLUGIN_RESULTS)
                    or ("wait_seconds" in result and (type(wait_seconds) is not int or is_followup
                        or wait_seconds not in (SUDO_DIRECT_REPLY_TIMEOUT, SUDO_FOLLOWUP_TIMEOUT)
                        or status not in {"ok", "partial", "scheduled", "error"}))
                    or ("sequence" in result and (not is_followup or type(sequence) is not int
                        or not 1 <= sequence <= MAX_FOLLOWUP_FRAMES))
                    or (("final" in result) != ("sequence" in result))
                    or type(final) is not bool
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
                        if not work.publish(reply, final=final, sequence=sequence):
                            await websocket.close(code=1007)
                            break
                    continue
                if (
                    pending is not None and pending.plugin is websocket and not pending.result.done()
                    and request_id == pending.request_id
                ):
                    if status == "scheduled" or wait_seconds is not None:
                        prune_followups()
                        if len(followups) >= MAX_FOLLOWUPS:
                            delivered = next((key for key, item in followups.items() if item.delivered_sequence), None)
                            if delivered is not None:
                                del followups[delivered]
                        if len(followups) < MAX_FOLLOWUPS:
                            followups[request_id] = Followup(
                                websocket, time.monotonic() + (wait_seconds or SUDO_FOLLOWUP_TIMEOUT) + SUDO_TIMEOUT,
                                streaming=wait_seconds is not None,
                            )
                            if wait_seconds is not None:
                                reply["wait_seconds"] = wait_seconds
                        elif status != "error":
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
