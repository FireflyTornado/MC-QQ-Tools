import asyncio
import json
import time
import unicodedata
from base64 import b64encode
from collections import OrderedDict
from dataclasses import dataclass
from uuid import uuid4

import h11
from nonebot import logger, on_command
from nonebot.adapters.onebot.v11 import Bot, GroupMessageEvent, MessageSegment
from nonebot.permission import Permission

from app_config import ConfigError, load_config
from qq_group import group_rule
from runtime_status import HEALTH_PORT
from sudo_config import (
    MAX_FOLLOWUP_FRAMES,
    MAX_RESULT_MESSAGE_BYTES,
    SUDO_DIRECT_REPLY_TIMEOUT,
    SUDO_FOLLOWUP_PATH,
    SUDO_FOLLOWUP_TIMEOUT,
    SUDO_SUBMIT_PATH,
    SUDO_TIMEOUT,
    SudoOutputSettings,
    is_single_command,
    read_sudo_output_settings,
    read_sudo_settings,
)

try:
    config = load_config()
    settings = read_sudo_settings(config)
    output_settings = read_sudo_output_settings(config) if settings else SudoOutputSettings()
except ConfigError as exc:
    logger.error("/sudo 已关闭：{}", exc)
    settings = None

running = asyncio.Lock()
followup_tasks: set[asyncio.Task] = set()
recent: OrderedDict[tuple[int, int, int], float] = OrderedDict()
REPLAY_WINDOW = 300
MAX_RECENT = 1024
replay_cache_blocked_until = 0.0
UPLOAD_TIMEOUT = 20
FOLLOWUP_RETRY_DELAY = 1
REPLIES = {
    "ok": "命令已返回，服务端无输出。",
    "rejected": "该指令或参数未获服务端许可。",
    "error": "服务端执行失败或无法确认结果。",
    "busy": "有指令正在处理，请稍后重试。",
    "unavailable": "Minecraft 指令通道当前离线。",
    "disconnected": "指令通道已断开，执行结果未知。",
    "expired": "指令已过期，未执行。",
    "timeout": "执行超时，结果未知。",
    "partial": "命令已返回，输出采集不完整。",
    "scheduled": "任务已安排，执行后将补发结果。",
    "cancelled": "已安排的任务已取消。",
}


@dataclass(frozen=True)
class Reply:
    status: str
    output: str = ""
    truncated: bool = False
    wait_seconds: int | None = None
    sequence: int | None = None
    final: bool = True


@dataclass(frozen=True)
class RenderedReply:
    message: MessageSegment | None
    output_in_file: bool = False
    output_in_message: bool = False


def allowed(event: GroupMessageEvent) -> bool:
    return (
        settings is not None
        and event.sub_type == "normal"
        and event.anonymous is None
        and event.user_id != event.self_id
        and str(event.user_id) in settings.qq_ids
    )


sudo = on_command("sudo", rule=group_rule, permission=Permission(allowed), force_whitespace=True,
                  priority=10, block=True)


def command_from(event: GroupMessageEvent) -> str | None:
    message = event.original_message
    if not message or not all(segment.is_text() for segment in message):
        return None
    prefix, _, command = message.extract_plain_text().partition(" ")
    if prefix != "/sudo":
        return None
    command = command.strip(" ")
    return command if is_single_command(command) else None


async def submit(token: str, payload: dict) -> Reply:
    return await request_result(token, "POST", SUDO_SUBMIT_PATH, payload)


async def request_result(token: str, method: str, path: str, payload: dict | None = None) -> Reply:
    # Socket I/O is cancellable under the handler's total deadline.
    try:
        reader, writer = await asyncio.open_connection("127.0.0.1", HEALTH_PORT)
        try:
            protocol = h11.Connection(h11.CLIENT)
            body = json.dumps(payload).encode("utf-8") if payload is not None else b""
            request = h11.Request(method=method, target=path, headers=[
                ("Host", f"127.0.0.1:{HEALTH_PORT}"),
                ("Authorization", f"Bearer {token}"),
                ("Content-Type", "application/json"),
                ("Content-Length", str(len(body))),
                ("Connection", "close"),
            ])
            writer.write(protocol.send(request))
            if body:
                writer.write(protocol.send(h11.Data(data=body)))
            writer.write(protocol.send(h11.EndOfMessage()))
            await writer.drain()

            raw = bytearray()
            while True:
                event = protocol.next_event()
                if event is h11.NEED_DATA:
                    protocol.receive_data(await reader.read(8192))
                elif isinstance(event, h11.Response) and event.status_code != 200:
                    return Reply("unavailable" if event.status_code in {401, 403, 404} else "unknown")
                elif isinstance(event, h11.Data):
                    if len(raw) + len(event.data) > MAX_RESULT_MESSAGE_BYTES:
                        return Reply("unknown")
                    raw.extend(event.data)
                elif isinstance(event, h11.EndOfMessage):
                    break
                elif isinstance(event, h11.ConnectionClosed) or event is h11.PAUSED:
                    return Reply("unknown")
            result = json.loads(raw)
        finally:
            # Abort immediately, including if cancellation interrupted a write or read.
            writer.transport.abort()
    except (OSError, h11.ProtocolError, ValueError, RecursionError):
        return Reply("unknown")
    if isinstance(result, dict):
        status, output = result.get("status"), result.get("output", "")
        truncated = result.get("truncated", False)
        wait_seconds = result.get("wait_seconds")
        sequence = result.get("sequence")
        final = result.get("final", True)
        if (
            isinstance(status, str) and (status in REPLIES or (method == "GET" and status in {"waiting", "closed"}))
            and isinstance(output, str)
            and type(truncated) is bool
            and (wait_seconds is None or (method == "POST" and type(wait_seconds) is int
                 and wait_seconds in {SUDO_DIRECT_REPLY_TIMEOUT, SUDO_FOLLOWUP_TIMEOUT}))
            and (sequence is None or (method == "GET" and type(sequence) is int and 1 <= sequence <= MAX_FOLLOWUP_FRAMES))
            and type(final) is bool and (final or sequence is not None)
            and not any(0xD800 <= ord(char) <= 0xDFFF for char in output)
        ):
            return Reply(status, output, truncated, wait_seconds, sequence, final)
    return Reply("unknown")


async def upload_output(bot: Bot, group_id: int, request_id: str, output: str) -> bool:
    try:
        async with asyncio.timeout(UPLOAD_TIMEOUT):
            result = await bot.call_api(
                "upload_group_file",
                group_id=str(group_id),
                name=f"sudo-{request_id}.txt",
                file="base64://" + b64encode(output.encode("utf-8")).decode("ascii"),
            )
    except Exception as exc:
        logger.warning("/sudo #{} 群文件上传失败（{}）", request_id, type(exc).__name__)
        return False
    if isinstance(result, dict) and isinstance(result.get("file_id"), str) and result["file_id"]:
        return True
    logger.warning("/sudo #{} 群文件上传未返回文件 ID", request_id)
    return False


async def render_result(bot: Bot, group_id: int, request_id: str, status: str, output: str,
                        truncated: bool, *, followup: bool = False, sequence: int | None = None,
                        waiting: bool = False) -> RenderedReply:
    text = "".join(
        char for char in output if char in "\r\n\t" or unicodedata.category(char) not in {"Cc", "Cs"}
    )
    output_in_file = False
    output_in_message = bool(text.strip())
    failed_with_followup = status == "error" and waiting
    if truncated or (output and not text.strip()) or output_settings.use_file(output):
        output_in_message = False
        filename_id = f"{request_id}-followup" if followup else request_id
        if sequence is not None:
            filename_id += f"-{sequence}"
        if await upload_output(bot, group_id, filename_id, output):
            output_in_file = True
            if followup:
                text = "输出已上传为文件。"
            elif not failed_with_followup and status not in {"partial", "scheduled"}:
                return RenderedReply(None, output_in_file=True)
            else:
                text = ""
        elif followup:
            return RenderedReply(None)
        else:
            text = f"结果文件上传失败或未确认。请求编号：{request_id}"
    if not text.strip():
        if waiting and not failed_with_followup and not output and not truncated:
            return RenderedReply(MessageSegment.text("正在执行..."))
        text = {
            "expired": "等待期间未捕获输出",
            "ok": "已安排的任务执行完毕，服务端无输出。",
            "disconnected": "指令通道已断开，无法接收任务后续结果。",
        }.get(status, REPLIES[status]) if followup else REPLIES[status]
    elif failed_with_followup or status in {"partial", "scheduled"}:
        if not text.endswith("\n"):
            text += "\n"
        text += REPLIES[status]
    if status == "scheduled":
        text += f" 请求编号：{request_id}"
    if failed_with_followup:
        text += f"\n后台任务的结果会继续补发。请求编号：{request_id}"
    if followup:
        text = f"指令后续回执 {request_id}\n{text}"
    return RenderedReply(MessageSegment.text(text), output_in_file, output_in_message)


async def send_followup(bot: Bot, group_id: int, request_id: str, reply: Reply, deadline: float) -> bool:
    loop = asyncio.get_running_loop()
    delay = FOLLOWUP_RETRY_DELAY
    message = None
    while (remaining := deadline - loop.time()) > 0:
        try:
            async with asyncio.timeout(min(UPLOAD_TIMEOUT, remaining)):
                if message is None:
                    rendered = await render_result(bot, group_id, request_id, reply.status, reply.output,
                                                   reply.truncated, followup=True, sequence=reply.sequence)
                    message = rendered.message
                if message is not None:
                    await bot.send_group_msg(group_id=group_id, message=message)
                    return True
        except Exception as exc:
            logger.warning("/sudo #{} 后续结果 {} 发送失败（{}）", request_id, reply.sequence, type(exc).__name__)
        await asyncio.sleep(max(0, min(delay, deadline - loop.time())))
        delay = min(delay * 2, 15)
    logger.warning("/sudo #{} 后续结果 {} 投递到期，未确认发送", request_id, reply.sequence)
    return False


async def deliver_followup(bot: Bot, group_id: int, token: str, request_id: str, initial: Reply,
                           *, delivered_output: bool) -> None:
    path = f"{SUDO_FOLLOWUP_PATH}/{request_id}"
    after = 0 if initial.wait_seconds is not None else None
    loop = asyncio.get_running_loop()
    deadline = loop.time() + (initial.wait_seconds or SUDO_FOLLOWUP_TIMEOUT) + SUDO_TIMEOUT
    while (remaining := deadline - loop.time()) > 0:
        try:
            async with asyncio.timeout(min(20, remaining)):
                reply = await request_result(token, "GET", path if after is None else f"{path}?after={after}")
        except TimeoutError:
            reply = Reply("unknown")
        if reply.status in {"waiting", "unknown", "unavailable"}:
            if reply.status != "waiting":
                await asyncio.sleep(max(0, min(2, deadline - loop.time())))
            continue
        if reply.status == "closed":
            return
        if reply.sequence is not None and (after is None or reply.sequence != after + 1):
            await asyncio.sleep(max(0, min(2, deadline - loop.time())))
            continue
        has_output = bool(reply.output or reply.truncated)
        if has_output or not delivered_output or reply.status not in {"ok", "expired"}:
            # A frame fetched near the end of capture still gets time to retry delivery.
            deadline = max(deadline, loop.time() + SUDO_TIMEOUT)
            if not await send_followup(bot, group_id, request_id, reply, deadline):
                return
            deadline = max(deadline, loop.time() + SUDO_TIMEOUT)
            delivered_output |= has_output
        if reply.sequence is not None:
            after = reply.sequence
        if reply.final:
            return
    if not delivered_output:
        await send_followup(bot, group_id, request_id, Reply("expired"), loop.time() + SUDO_TIMEOUT)


@sudo.handle()
async def handle_sudo(bot: Bot, event: GroupMessageEvent) -> None:
    global replay_cache_blocked_until

    if settings is None:
        return
    command = command_from(event)
    if command is None:
        await sudo.finish(MessageSegment.text("用法：/sudo <一条纯文本 Minecraft 指令>"))
        return
    now = time.monotonic()
    while recent and next(iter(recent.values())) <= now - REPLAY_WINDOW:
        recent.popitem(last=False)
    key = (event.self_id, event.group_id, event.message_id)
    if key in recent:
        return
    if len(recent) >= MAX_RECENT or now < replay_cache_blocked_until:
        # Reject untracked messages until their redelivery window has passed.
        replay_cache_blocked_until = now + REPLAY_WINDOW
        await sudo.finish(MessageSegment.text("指令请求过多，暂无法受理。请等待至少 5 分钟后重新发送。"))
        return
    recent[key] = now

    if running.locked():
        await sudo.finish(MessageSegment.text(REPLIES["busy"]))
        return
    async with running:
        request_id = uuid4().hex
        payload = {
            "id": request_id,
            "group_id": str(event.group_id),
            "qq_id": str(event.user_id),
            "command": command,
            "deadline_ms": time.time_ns() // 1_000_000 + SUDO_TIMEOUT * 1000,
        }
        try:
            async with asyncio.timeout(SUDO_TIMEOUT) as wait:
                reply = await submit(settings.bot_token, payload)
                if reply.status == "unknown":
                    # A broken HTTP response cannot tell us whether the plugin executed.
                    await asyncio.sleep(max(0, wait.when() - asyncio.get_running_loop().time()))
                    reply = Reply("timeout")
        except TimeoutError:
            reply = Reply("timeout")
        logger.info("/sudo #{} qq={} result={}", request_id, event.user_id, reply.status)
        waiting = reply.wait_seconds is not None or reply.status == "scheduled"
        rendered = await render_result(bot, event.group_id, request_id, reply.status, reply.output, reply.truncated,
                                       waiting=waiting)
        if waiting:
            delivered_output = rendered.output_in_file
            try:
                if rendered.message is not None:
                    await sudo.send(rendered.message)
                    delivered_output |= rendered.output_in_message
            finally:
                task = asyncio.create_task(deliver_followup(bot, event.group_id, settings.bot_token, request_id, reply,
                                                           delivered_output=delivered_output))
                followup_tasks.add(task)
                task.add_done_callback(followup_tasks.discard)
        elif rendered.message is not None:
            await sudo.finish(rendered.message)
