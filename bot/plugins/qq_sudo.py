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

from app_config import API_PORT
from qq_group import group_rule
from sudo_config import (
    MAX_FOLLOWUP_FRAMES,
    MAX_RESULT_MESSAGE_BYTES,
    SUDO_DIRECT_REPLY_TIMEOUT,
    SUDO_FOLLOWUP_PATH,
    SUDO_FOLLOWUP_TIMEOUT,
    SUDO_SUBMIT_PATH,
    SUDO_TIMEOUT,
    SudoOutputSettings,
    SudoSettings,
    is_single_command,
)

settings: SudoSettings | None = None
output_settings = SudoOutputSettings()


def configure(sudo_settings: SudoSettings, output: SudoOutputSettings) -> None:
    global settings, output_settings
    settings, output_settings = sudo_settings, output

running = asyncio.Lock()
followup_tasks: set[asyncio.Task] = set()
recent: OrderedDict[tuple[int, int, int], float] = OrderedDict()
REPLAY_WINDOW = 300
MAX_RECENT = 1024
replay_cache_blocked_until = 0.0
UPLOAD_TIMEOUT = 20
FOLLOWUP_UPLOAD_ATTEMPTS = 3
FOLLOWUP_RETRY_DELAY = 1
TRACKED_FOLLOWUP_TIMEOUT_NOTICE = "等待任务回传超时。"
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
FOLLOWUP_REPLIES = REPLIES | {
    "expired": "等待期间未捕获输出",
    "ok": "已安排的任务执行完毕，服务端无输出。",
    "disconnected": "指令通道已断开，无法接收任务后续结果。",
}
INITIAL_STATUS_NOTICES = {
    "error": "首轮命令执行失败或无法确认结果。",
    "partial": REPLIES["partial"],
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
        reader, writer = await asyncio.open_connection("127.0.0.1", API_PORT)
        try:
            protocol = h11.Connection(h11.CLIENT)
            body = json.dumps(payload).encode("utf-8") if payload is not None else b""
            request = h11.Request(method=method, target=path, headers=[
                ("Host", f"127.0.0.1:{API_PORT}"),
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


async def upload_output(bot: Bot, group_id: int, request_id: str, output: str, *, timeout: float) -> bool:
    try:
        async with asyncio.timeout(timeout):
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


async def upload_followup(bot: Bot, group_id: int, filename_id: str, output: str) -> bool:
    loop = asyncio.get_running_loop()
    deadline = loop.time() + UPLOAD_TIMEOUT
    for attempt in range(FOLLOWUP_UPLOAD_ATTEMPTS):
        remaining = deadline - loop.time()
        if remaining <= 0:
            break
        if await upload_output(bot, group_id, filename_id, output, timeout=remaining):
            return True
        if attempt + 1 < FOLLOWUP_UPLOAD_ATTEMPTS:
            delay = FOLLOWUP_RETRY_DELAY * 2 ** attempt
            await asyncio.sleep(max(0, min(delay, deadline - loop.time())))
    return False


async def render_result(bot: Bot, group_id: int, request_id: str, reply: Reply, *, followup: bool = False,
                        waiting: bool = False, terminal_notice: str | None = None,
                        pending_initial_notice: str | None = None) -> RenderedReply:
    status, output = reply.status, reply.output
    text = "".join(
        char for char in output if char in "\r\n\t" or unicodedata.category(char) not in {"Cc", "Cs"}
    )
    output_in_file = False
    output_in_message = bool(text.strip())
    file_upload_failed = False
    failed_with_followup = status == "error" and waiting
    if reply.truncated or (output and not text.strip()) or output_settings.use_file(output):
        output_in_message = False
        filename_id = f"{request_id}-followup" if followup else request_id
        if reply.sequence is not None:
            filename_id += f"-{reply.sequence}"
        if followup:
            output_in_file = await upload_followup(bot, group_id, filename_id, output)
        else:
            output_in_file = await upload_output(bot, group_id, filename_id, output, timeout=UPLOAD_TIMEOUT)
        if output_in_file:
            if followup:
                text = "输出已上传为文件。"
            elif not failed_with_followup and status not in {"partial", "scheduled"}:
                return RenderedReply(None, output_in_file=True)
            else:
                text = ""
        else:
            file_upload_failed = True
            text = f"结果文件上传失败或未确认。请求编号：{request_id}"
            if followup and reply.final and terminal_notice is None:
                terminal_notice = {
                    "ok": "已安排的任务执行完毕。",
                    "expired": "等待后续回传超时。",
                }.get(status, FOLLOWUP_REPLIES[status])
    if not text.strip():
        if waiting and status in {"ok", "scheduled"} and not output and not reply.truncated:
            text = "正在执行..."
        else:
            text = terminal_notice or (FOLLOWUP_REPLIES if followup else REPLIES)[status]
            if waiting and status == "partial" and not output and not reply.truncated:
                text += "\n后续输出仍会补发。"
    elif terminal_notice is not None or failed_with_followup or status in {"partial", "scheduled"}:
        if not text.endswith("\n"):
            text += "\n"
        text += terminal_notice or REPLIES[status]
    if failed_with_followup:
        if reply.wait_seconds == SUDO_DIRECT_REPLY_TIMEOUT:
            text += "\n如果收到后续输出，会继续补发。"
        else:
            text += "\n后台任务的结果会继续补发。"
    if followup:
        if pending_initial_notice is not None:
            text = f"{pending_initial_notice}\n{text}"
        text = f"指令后续回执 {request_id}\n{text}"
    elif waiting and not file_upload_failed:
        if not text.endswith("\n"):
            text += "\n"
        text += f"请求编号：{request_id}"
    return RenderedReply(MessageSegment.text(text), output_in_file, output_in_message)


async def send_followup(bot: Bot, group_id: int, request_id: str, reply: Reply, deadline: float,
                        *, terminal_notice: str | None = None, pending_initial_notice: str | None = None) -> bool:
    loop = asyncio.get_running_loop()
    delay = FOLLOWUP_RETRY_DELAY
    rendered = await render_result(bot, group_id, request_id, reply, followup=True, terminal_notice=terminal_notice,
                                   pending_initial_notice=pending_initial_notice)
    assert rendered.message is not None  # Follow-up files always have a text receipt.
    # File retries have their own budget. Keep the full text delivery window after rendering.
    deadline = max(deadline, loop.time() + SUDO_TIMEOUT)
    while (remaining := deadline - loop.time()) > 0:
        try:
            async with asyncio.timeout(min(UPLOAD_TIMEOUT, remaining)):
                await bot.send_group_msg(group_id=group_id, message=rendered.message)
                return True
        except Exception as exc:
            logger.warning("/sudo #{} 后续结果 {} 发送失败（{}）", request_id, reply.sequence, type(exc).__name__)
        await asyncio.sleep(max(0, min(delay, deadline - loop.time())))
        delay = min(delay * 2, 15)
    logger.warning("/sudo #{} 后续结果 {} 投递到期，未确认发送", request_id, reply.sequence)
    return False


async def confirm_followup(token: str, request_id: str, sequence: int) -> None:
    # Reading after the terminal sequence acknowledges delivery, even for a quiet terminal frame.
    path = f"{SUDO_FOLLOWUP_PATH}/{request_id}?after={sequence}"
    try:
        async with asyncio.timeout(SUDO_TIMEOUT):
            while True:
                reply = await request_result(token, "GET", path)
                if reply.status in {"closed", "expired"}:
                    return
                await asyncio.sleep(2)
    except TimeoutError:
        logger.warning("/sudo #{} 未能确认补发终帧清理", request_id)


async def deliver_followup(bot: Bot, group_id: int, token: str, request_id: str, initial: Reply,
                           *, delivered_output: bool, pending_initial_notice: str | None = None) -> None:
    path = f"{SUDO_FOLLOWUP_PATH}/{request_id}"
    after = 0 if initial.wait_seconds is not None else None
    tracked_task = initial.status == "scheduled" or initial.wait_seconds == SUDO_FOLLOWUP_TIMEOUT
    reported_output = delivered_output
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
        terminal_notice = None
        if tracked_task and reply.final:
            if reply.status == "expired":
                terminal_notice = TRACKED_FOLLOWUP_TIMEOUT_NOTICE
            elif reply.status == "ok" and not has_output:
                terminal_notice = "已安排的任务执行完毕。" if reported_output else FOLLOWUP_REPLIES["ok"]
        if (terminal_notice is not None or pending_initial_notice is not None or has_output or not reported_output
                or reply.status not in {"ok", "expired"}):
            if not await send_followup(bot, group_id, request_id, reply, deadline, terminal_notice=terminal_notice,
                                       pending_initial_notice=pending_initial_notice):
                return
            deadline = max(deadline, loop.time() + SUDO_TIMEOUT)
            # A confirmed file failure notice also tells the group that output existed.
            reported_output |= has_output
            pending_initial_notice = None
        if reply.sequence is not None:
            after = reply.sequence
        if reply.final:
            if reply.sequence is not None or after is None:
                await confirm_followup(token, request_id, reply.sequence or 1)
            return
    if tracked_task or pending_initial_notice is not None or not reported_output:
        await send_followup(bot, group_id, request_id, Reply("expired"), loop.time() + SUDO_TIMEOUT,
                            terminal_notice=TRACKED_FOLLOWUP_TIMEOUT_NOTICE if tracked_task else None,
                            pending_initial_notice=pending_initial_notice)


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
        rendered = await render_result(bot, event.group_id, request_id, reply, waiting=waiting)
        if waiting:
            delivered_output = rendered.output_in_file
            pending_initial_notice = INITIAL_STATUS_NOTICES.get(reply.status)
            try:
                if rendered.message is not None:
                    await sudo.send(rendered.message)
                    delivered_output |= rendered.output_in_message
                    pending_initial_notice = None
            finally:
                task = asyncio.create_task(deliver_followup(
                    bot, event.group_id, settings.bot_token, request_id, reply, delivered_output=delivered_output,
                    pending_initial_notice=pending_initial_notice,
                ))
                followup_tasks.add(task)
                task.add_done_callback(followup_tasks.discard)
        elif rendered.message is not None:
            await sudo.finish(rendered.message)
