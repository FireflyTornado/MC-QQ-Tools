import asyncio
import time
from unicodedata import category
from weakref import WeakKeyDictionary

from nonebot import get_driver, logger, on_command
from nonebot.adapters.onebot.v11 import Bot, MessageSegment
from nonebot.permission import SUPERUSER

import message_config
from qq_avatar import get_avatar, is_qq_id
from qq_group import TARGET_GROUP_ID, group_rule, message_variables
from runtime_status import check_fastapi, collect_status
from status_image import render_status


driver = get_driver()
connected_at: WeakKeyDictionary[Bot, float] = WeakKeyDictionary()
info_lock = asyncio.Lock()
LOGIN_INFO_TIMEOUT = 2
auth_enabled = True
sudo_enabled = False


def configure(*, auth: bool, sudo: bool) -> None:
    global auth_enabled, sudo_enabled
    auth_enabled, sudo_enabled = auth, sudo


@driver.on_bot_connect
async def remember_connection(bot: Bot) -> None:
    connected_at[bot] = time.monotonic()


@driver.on_bot_disconnect
async def forget_connection(bot: Bot) -> None:
    connected_at.pop(bot, None)


help_command = on_command("帮助", aliases={"help"}, rule=group_rule, priority=10, block=True)
ping = on_command("认证测试", aliases={"ping"}, rule=group_rule, priority=10, block=True)
info = on_command("信息", aliases={"info"}, rule=group_rule, permission=SUPERUSER, priority=10, block=True)


@help_command.handle()
async def handle_help(bot: Bot) -> None:
    variables = message_variables(bot)
    messages = message_config.MESSAGES
    key = "commands.help.message" if auth_enabled else "commands.help.shared"
    reply = messages.render(key, **variables)
    if sudo_enabled:
        reply += MessageSegment.text("\n")
        reply += messages.render("commands.help.sudo", **variables)
    await help_command.finish(reply)


@ping.handle()
async def handle_ping(bot: Bot) -> None:
    result = await asyncio.to_thread(check_fastapi)
    if result.status_code is not None:
        outcome = "success"
    else:
        outcome = {"Timeout": "timeout", "Connection failed": "connection_error"}.get(result.error, "error")
    await ping.finish(message_config.MESSAGES.render(
        f"commands.ping.{outcome}", **message_variables(bot), status_code=result.status_code,
        latency_ms=round(result.latency_ms),
    ))


async def _nickname(bot: Bot) -> str | None:
    try:
        async with asyncio.timeout(LOGIN_INFO_TIMEOUT):
            identity = await bot.get_login_info()
    except Exception as exc:
        logger.debug("[MC-QQ-Tools] OneBot 登录信息不可用（{}）", type(exc).__name__)
        return None
    if not isinstance(identity, dict) or str(identity.get("user_id")) != bot.self_id:
        return None
    nickname = identity.get("nickname")
    if not isinstance(nickname, str):
        return None
    nickname = "".join(ch for ch in nickname[:256] if category(ch) not in {"Cc", "Cf", "Cs"})
    return nickname.strip() or None


def _status_png(qq_id: str, nickname: str | None, connected: bool, connected_for: float | None) -> bytes:
    avatar = get_avatar(qq_id)
    group_id = str(TARGET_GROUP_ID)
    status = collect_status(
        group_id, connected=connected, connected_for=connected_for,
        auth_enabled=auth_enabled, sudo_enabled=sudo_enabled,
    )
    return render_status(status, group_id=group_id, qq_id=qq_id, nickname=nickname, avatar=avatar)


@info.handle()
async def handle_info(bot: Bot) -> None:
    variables = message_variables(bot)
    if info_lock.locked():
        await info.finish(message_config.MESSAGES.render("commands.info.busy", **variables))
    async with info_lock:
        qq_id = bot.self_id if is_qq_id(bot.self_id) else ""
        nickname = await _nickname(bot) if qq_id and driver.bots.get(qq_id) is bot else None
        connected = driver.bots.get(bot.self_id) is bot
        started = connected_at.get(bot)
        connected_for = time.monotonic() - started if connected and started is not None else None
        try:
            png = await asyncio.to_thread(_status_png, qq_id, nickname, connected, connected_for)
        except Exception as exc:
            logger.warning("[MC-QQ-Tools] 状态图生成失败（{}）", type(exc).__name__)
            await info.finish(message_config.MESSAGES.render("commands.info.error", **variables))
    await info.finish(MessageSegment.image(png, cache=False))
