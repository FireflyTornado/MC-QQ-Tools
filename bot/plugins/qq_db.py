import asyncio
import sqlite3

from nonebot import logger, on_command
from nonebot.adapters.onebot.v11 import Bot, GroupMessageEvent, Message
from nonebot.params import CommandArg
from nonebot.permission import SUPERUSER

import message_config
from binding_service import resolve_binding
from database import valid_mc_name
from qq_avatar import is_qq_id
from qq_group import group_rule, member_name_variables, message_variables

from .qq_events import bind_verified_member


db = on_command("db", rule=group_rule, permission=SUPERUSER, force_whitespace=True, priority=10, block=True)


def lookup_keys(value: str) -> tuple[str | None, str | None]:
    prefix, separator, key = value.partition(":")
    if not separator:
        key = value
    qq_id = key if (not separator or prefix == "qq") and is_qq_id(key) else None
    mc_name = key if (not separator or prefix == "mc") and valid_mc_name(key) else None
    return qq_id, mc_name


@db.handle()
async def handle_db(bot: Bot, event: GroupMessageEvent, args: Message = CommandArg()) -> None:
    messages = message_config.MESSAGES
    variables = message_variables(bot)
    if not all(segment.is_text() for segment in event.original_message):
        await db.finish(messages.render("commands.db.usage", **variables))
    parts = args.extract_plain_text().split()
    action = parts[0] if parts else ""
    if action not in {"add", "get", "rm"}:
        await db.finish(messages.render("commands.db.usage", **variables))
    if len(parts) != (3 if action == "add" else 2):
        await db.finish(messages.render(f"commands.db.{action}_usage", **variables))

    qq_id = mc_name = None
    try:
        if action == "add":
            qq_id, name = parts[1:]
            if not is_qq_id(qq_id):
                await db.finish(messages.render("commands.db.invalid_qq", **variables))
            if not valid_mc_name(name):
                await db.finish(messages.render("commands.db.invalid_name", **variables))
            mc_name = name
            status, existing_name = await bind_verified_member(bot, qq_id, name)
            mc_name = existing_name or name
            if status == "already_bound" and mc_name.lower() == name.lower():
                status = "already_same"
        else:
            qq_key, mc_key = lookup_keys(parts[1])
            if qq_key is None and mc_key is None:
                await db.finish(messages.render(f"commands.db.{action}_usage", **variables))
            status, record = await asyncio.to_thread(
                resolve_binding, str(event.group_id), qq_id=qq_key, mc_name=mc_key, remove=action == "rm",
            )
            if record is not None:
                qq_id, mc_name = record.qq_id, record.mc_name
            if action == "rm" and status in {"frozen", "pending"}:
                status = f"rm_{status}"
    except sqlite3.Error:
        logger.exception("[MC-QQ-AUTH] /db 数据库操作失败")
        status = "error"

    reply_key = f"commands.db.{status}"
    reply_values = dict(variables, member_qq=qq_id, mc_name=mc_name)
    if action != "get":
        logger.info(
            "/db {} operator={} group={} target={!r} qq={!r} mc_name={!r} result={}",
            action, event.user_id, event.group_id, " ".join(parts[1:]), qq_id, mc_name, status,
        )
    if qq_id is not None:
        if is_qq_id(qq_id):
            group_id = None if status == "not_member" else event.group_id
            reply_values.update(await member_name_variables(
                bot, messages, reply_key, int(qq_id), group_id=group_id, values=reply_values,
            ))
        else:
            reply_values.update(member_name="", member_display=qq_id)
    await db.finish(messages.render(reply_key, **reply_values))
