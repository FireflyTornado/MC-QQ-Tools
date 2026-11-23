import asyncio
import sqlite3

from nonebot import logger, on_command
from nonebot.adapters.onebot.v11 import (
    Bot,
    GroupMessageEvent,
    Message,
)
from nonebot.params import CommandArg

from binding_service import (
    bind_player,
    query_binding,
    request_unbind,
)
import message_config
from qq_group import group_rule, message_variables


# ========================================
# /绑定 玩家名
# ========================================

bind = on_command(
    "绑定",
    aliases={"bind"},
    rule=group_rule,
    priority=10,
    block=True,
)


@bind.handle()
async def handle_bind(
    bot: Bot,
    event: GroupMessageEvent,
    args: Message = CommandArg(),
):
    mc_name = args.extract_plain_text().strip()
    variables = message_variables(bot)

    if not mc_name:
        await bind.finish(message_config.MESSAGES.render("commands.bind.usage", **variables))

    group_id = str(event.group_id)
    qq_id = str(event.user_id)

    try:
        status, value = await asyncio.to_thread(
            bind_player,
            group_id,
            qq_id,
            mc_name,
        )
    except sqlite3.Error:
        logger.exception("绑定数据库操作失败")
        await bind.finish(message_config.MESSAGES.render("commands.bind.error", **variables))

    await bind.finish(message_config.MESSAGES.render(
        f"commands.bind.{status}", **variables, mc_name=mc_name if status == "banned_name" else value,
    ))


# ========================================
# /我的绑定
# ========================================

query = on_command(
    "我的绑定",
    aliases={"binding"},
    rule=group_rule,
    priority=10,
    block=True,
)


@query.handle()
async def handle_query(bot: Bot, event: GroupMessageEvent):
    variables = message_variables(bot)
    try:
        status, mc_name = await asyncio.to_thread(
            query_binding,
            str(event.group_id),
            str(event.user_id),
        )
    except sqlite3.Error:
        logger.exception("查询绑定数据库失败")
        await query.finish(message_config.MESSAGES.render("commands.binding.error", **variables))

    await query.finish(message_config.MESSAGES.render(f"commands.binding.{status}", **variables, mc_name=mc_name))


# ========================================
# /解绑
# ========================================

unbind = on_command(
    "解绑",
    aliases={"unbind"},
    rule=group_rule,
    priority=10,
    block=True,
)


@unbind.handle()
async def handle_unbind(bot: Bot, event: GroupMessageEvent):
    variables = message_variables(bot)
    try:
        status, mc_name = await asyncio.to_thread(
            request_unbind,
            str(event.group_id),
            str(event.user_id),
        )
    except sqlite3.Error:
        logger.exception("解绑数据库操作失败")
        await unbind.finish(message_config.MESSAGES.render("commands.unbind.error", **variables))

    await unbind.finish(message_config.MESSAGES.render(f"commands.unbind.{status}", **variables, mc_name=mc_name))
