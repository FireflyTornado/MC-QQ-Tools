import asyncio
import json
import logging
import re
import time
from collections import OrderedDict
from collections.abc import Callable
from dataclasses import dataclass, field
from itertools import islice
from uuid import uuid4

from fastapi import APIRouter, Header, HTTPException, Request, WebSocket, WebSocketDisconnect

from sudo_config import (
    MAX_FOLLOWUP_FRAMES,
    MAX_FOLLOWUPS,
    MAX_RESULT_BYTES,
    MAX_RESULT_MESSAGE_BYTES,
    SUDO_DIRECT_REPLY_TIMEOUT,
    SUDO_FOLLOWUP_PATH,
    SUDO_FOLLOWUP_TIMEOUT,
    SUDO_STOP_PATH,
    SUDO_SUBMIT_PATH,
    SUDO_TIMEOUT,
    SUDO_WS_PATH,
    SudoSettings,
    is_single_command,
)

logger = logging.getLogger("uvicorn.error.mc_qq_tools.sudo")
PLUGIN_RESULTS = {"ok", "rejected", "error", "busy", "expired", "partial", "scheduled"}
FOLLOWUP_RESULTS = {"ok", "error", "cancelled", "expired", "partial"}
MAX_MESSAGE_BYTES = 8192
SEND_TIMEOUT = 3
MAX_STOPPED_REQUESTS = 1024
STOP_CAPABILITY_HEADER = "x-mcqqsudo-capabilities"
STOP_CAPABILITY = "stop_followups"
FOLLOWUP_DELIVERY_RETENTION = SUDO_FOLLOWUP_TIMEOUT + 2 * SUDO_TIMEOUT
FOLLOWUP_CONFIRMATION_RETENTION = 300


@dataclass
class PendingCommand:
    request_id: str
    plugin: WebSocket
    result: asyncio.Future


@dataclass
class StopBatch:
    id: str
    request_ids: list[str]
    confirmed: asyncio.Event = field(default_factory=asyncio.Event)


@dataclass
class StopCleanup:
    pending: dict[str, None] = field(default_factory=dict)
    batch: StopBatch | None = None
    task: asyncio.Task[None] | None = None
    retry_requested: bool = False

    def acknowledge(self, stop_id: str) -> bool:
        batch = self.batch
        if batch is None or batch.id != stop_id:
            return False
        for request_id in batch.request_ids:
            self.pending.pop(request_id, None)
        self.batch = None
        batch.confirmed.set()
        return True


@dataclass
class Followup:
    plugin: WebSocket
    expires_at: float
    streaming: bool
    initial: dict
    frames: list[dict] = field(default_factory=list)
    changed: asyncio.Event = field(default_factory=asyncio.Event)
    finished: bool = False
    stopped: bool = False
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

    def stop(self) -> None:
        self.stopped = self.finished = True
        self.initial.clear()
        self.frames.clear()
        self.output_bytes = 0
        self.changed.set()

    def confirm_delivery(self, after: int | None) -> None:
        if not self.finished or self.stopped or self.delivered_sequence:
            return
        if after == len(self.frames):
            self.delivered_sequence = len(self.frames)
            self.initial.clear()
            self.frames.clear()
            self.output_bytes = 0
            self.expires_at = time.monotonic() + FOLLOWUP_CONFIRMATION_RETENTION
        else:
            # A poll may fetch a frame that still needs a lengthy QQ delivery attempt.
            self.expires_at = time.monotonic() + FOLLOWUP_DELIVERY_RETENTION

    def read(self, after: int | None) -> dict | None:
        if self.stopped:
            return {"status": "stopped"}
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


async def send_command(work: PendingCommand, send_lock: asyncio.Lock, command: str, deadline_ms: int) -> None:
    try:
        async with asyncio.timeout(SEND_TIMEOUT):
            async with send_lock:
                await work.plugin.send_json({"id": work.request_id, "command": command, "deadline_ms": deadline_ms,
                                             "stream_followup": True})
    except WebSocketDisconnect:
        if not work.result.done():
            work.result.set_result({"status": "disconnected"})
    except (TimeoutError, OSError, RuntimeError):
        # An interrupted write can still have reached the game server.
        logger.warning("/sudo #%s 未能确认指令已发送", work.request_id)


class SudoRouter(APIRouter):
    connected: bool = False


def sudo_router(
    settings: SudoSettings | None,
    target_group: str,
    verify_bearer: Callable[[list[str] | None, bytes], bool],
) -> SudoRouter:
    router = SudoRouter()
    mod_token = settings.mod_token.encode("ascii") if settings is not None and settings.mod_token is not None else None
    plugin: WebSocket | None = None
    send_lock = asyncio.Lock()
    pending: PendingCommand | None = None
    followups: dict[str, Followup] = {}
    stopped: OrderedDict[str, float] = OrderedDict()
    stop_cleanup: StopCleanup | None = None

    def prune_followups() -> None:
        now = time.monotonic()
        while stopped and next(iter(stopped.values())) <= now:
            stopped.popitem(last=False)
        for request_id, work in list(followups.items()):
            if work.expires_at <= now:
                if work.finished:
                    del followups[request_id]
                else:
                    work.complete({"status": "expired"})

    @router.get(SUDO_FOLLOWUP_PATH + "/{request_id}/state")
    async def followup_state(request_id: str, authorization: list[str] | None = Header(default=None)):
        if settings is None:
            raise HTTPException(404)
        if not verify_bearer(authorization, settings.bot_token.encode("ascii")):
            raise HTTPException(401)
        prune_followups()
        if request_id in stopped:
            return {"status": "stopped"}
        if (work := followups.get(request_id)) is not None:
            return work.initial or {"status": "closed"}
        return {"status": "expired"}

    @router.get(SUDO_FOLLOWUP_PATH + "/{request_id}")
    async def followup(request_id: str, after: int | None = None, authorization: list[str] | None = Header(default=None)):
        if settings is None:
            raise HTTPException(404)
        if not verify_bearer(authorization, settings.bot_token.encode("ascii")):
            raise HTTPException(401)
        if after is not None and not 0 <= after <= MAX_FOLLOWUP_FRAMES:
            raise HTTPException(400)
        prune_followups()
        if request_id in stopped:
            return {"status": "stopped"}
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

    async def authorized_payload(request: Request, authorization: list[str] | None) -> dict:
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
        return data

    async def stop_capture(socket: WebSocket, lock: asyncio.Lock, cleanup: StopCleanup) -> None:
        while cleanup.pending:
            cleanup.retry_requested = False
            if cleanup.batch is None:
                cleanup.batch = StopBatch(uuid4().hex, list(islice(cleanup.pending, MAX_FOLLOWUPS)))
            batch = cleanup.batch
            try:
                async with asyncio.timeout(SEND_TIMEOUT):
                    async with lock:
                        if plugin is not socket or not router.connected:
                            return
                        if not batch.confirmed.is_set():
                            await socket.send_json({"type": STOP_CAPABILITY, "id": batch.id,
                                                    "request_ids": batch.request_ids})
                    await batch.confirmed.wait()
            except (TimeoutError, OSError, RuntimeError, WebSocketDisconnect):
                if not batch.confirmed.is_set():
                    logger.warning("/sudostop #%s 游戏服侧清理未确认，requests=%s", batch.id, batch.request_ids)
                    if not cleanup.retry_requested:
                        return

    def schedule_stop_capture(socket: WebSocket, cleanup: StopCleanup, *, retry: bool = False) -> None:
        if cleanup.pending:
            if cleanup.task is None or cleanup.task.done():
                cleanup.task = asyncio.create_task(stop_capture(socket, send_lock, cleanup))
            elif retry:
                cleanup.retry_requested = True

    @router.post(SUDO_STOP_PATH)
    async def stop(request: Request, authorization: list[str] | None = Header(default=None)):
        data = await authorized_payload(request, authorization)
        prune_followups()
        # The snapshot and closure share one turn of the event loop with result handling.
        request_ids = [request_id for request_id, work in followups.items() if not work.finished]
        expires_at = time.monotonic() + FOLLOWUP_DELIVERY_RETENTION
        for request_id in request_ids:
            work = followups.pop(request_id)
            work.stop()
            stopped[request_id] = expires_at
            if stop_cleanup is not None and work.plugin is plugin:
                stop_cleanup.pending[request_id] = None
        while len(stopped) > MAX_STOPPED_REQUESTS:
            stopped.popitem(last=False)
        if request_ids:
            logger.info("/sudostop group=%s qq=%s requests=%s", target_group, data["qq_id"], request_ids)
            if stop_cleanup is None:
                logger.warning("/sudostop 游戏服侧清理未确认，requests=%s", request_ids)
        if plugin is not None and router.connected and stop_cleanup is not None:
            schedule_stop_capture(plugin, stop_cleanup, retry=True)
        return {"status": "stopped", "request_ids": request_ids}

    @router.post(SUDO_SUBMIT_PATH)
    async def submit(request: Request, authorization: list[str] | None = Header(default=None)):
        nonlocal pending
        data = await authorized_payload(request, authorization)
        qq_id = data["qq_id"]
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
        if (request_id in followups or request_id in stopped
                or (stop_cleanup is not None and request_id in stop_cleanup.pending)):
            return {"status": "rejected"}
        if plugin is None or not router.connected:
            return {"status": "unavailable"}

        work = PendingCommand(request_id, plugin, asyncio.get_running_loop().create_future())
        pending = work
        logger.info("/sudo #%s group=%s qq=%s command=%r", request_id, target_group, qq_id, command)
        status = "timeout"
        try:
            async with asyncio.timeout(remaining / 1000):
                async with asyncio.TaskGroup() as tasks:
                    sending = tasks.create_task(send_command(work, send_lock, command, deadline_ms))
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
        nonlocal plugin, send_lock, stop_cleanup
        if (
            mod_token is None or plugin is not None
            or not verify_bearer(websocket.headers.getlist("authorization"), mod_token)
        ):
            await websocket.close(code=1008)
            return

        plugin = websocket
        send_lock = asyncio.Lock()
        cleanup = StopCleanup() if STOP_CAPABILITY in {
            capability.strip() for header in websocket.headers.getlist(STOP_CAPABILITY_HEADER)
            for capability in header.split(",")
        } else None
        stop_cleanup = cleanup
        try:
            await websocket.accept()
            router.connected = True
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
                if result.get("type") == "stop_followups_ack":
                    stop_id = result.get("id")
                    if not isinstance(stop_id, str) or re.fullmatch(r"[0-9a-f]{32}", stop_id) is None:
                        await websocket.close(code=1007)
                        break
                    if cleanup is not None and cleanup.acknowledge(stop_id):
                        schedule_stop_capture(websocket, cleanup)
                    continue
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
                            if wait_seconds is not None:
                                reply["wait_seconds"] = wait_seconds
                            followups[request_id] = Followup(
                                websocket, time.monotonic() + (wait_seconds or SUDO_FOLLOWUP_TIMEOUT) + SUDO_TIMEOUT,
                                streaming=wait_seconds is not None,
                                initial=reply.copy(),
                            )
                        elif status != "error":
                            reply["status"] = "partial"
                    pending.result.set_result(reply)
        finally:
            router.connected = False
            plugin = None
            stop_cleanup = None
            if pending is not None and pending.plugin is websocket and not pending.result.done():
                pending.result.set_result({"status": "disconnected"})
            for work in followups.values():
                if work.plugin is websocket:
                    work.complete({"status": "disconnected"})
            if cleanup is not None and cleanup.task is not None:
                cleanup.task.cancel()
                await asyncio.gather(cleanup.task, return_exceptions=True)

    return router
