from __future__ import annotations

import asyncio
from collections.abc import Mapping
from typing import TYPE_CHECKING
from unicodedata import category

from nonebot import get_driver
from nonebot.adapters import Event
from nonebot.adapters.onebot.v11 import Bot, GroupMessageEvent
from nonebot.adapters.onebot.v11.exception import OneBotV11AdapterException
from nonebot.rule import Rule

if TYPE_CHECKING:
    from message_config import MessageConfig, MessageValue


TARGET_GROUP_ID = int(get_driver().config.target_group_id)
MEMBER_NAME_TIMEOUT = 2
MAX_MEMBER_NAME_LENGTH = 96


def message_variables(bot: Bot) -> dict[str, str]:
    return {"group_id": str(TARGET_GROUP_ID), "bot_qq": bot.self_id}


def _display_name(value: object) -> str:
    if not isinstance(value, str):
        return ""
    return "".join(
        char for char in value[:MAX_MEMBER_NAME_LENGTH] if category(char) not in {"Cc", "Cf", "Cs", "Zl", "Zp"}
    ).strip()


async def lookup_member_name(bot: Bot, group_id: int | None, user_id: int) -> str:
    """Get a group card or QQ nickname for display. Missing names are empty strings."""
    if group_id is not None:
        try:
            async with asyncio.timeout(MEMBER_NAME_TIMEOUT):
                member = await bot.get_group_member_info(group_id=group_id, user_id=user_id, no_cache=True)
        except (TimeoutError, OneBotV11AdapterException):
            pass
        else:
            if (isinstance(member, dict) and str(member.get("group_id")) == str(group_id)
                    and str(member.get("user_id")) == str(user_id)):
                name = _display_name(member.get("card")) or _display_name(member.get("nickname"))
                if name:
                    return name

    try:
        async with asyncio.timeout(MEMBER_NAME_TIMEOUT):
            profile = await bot.get_stranger_info(user_id=user_id)
    except (TimeoutError, OneBotV11AdapterException):
        return ""
    if isinstance(profile, dict) and str(profile.get("user_id")) == str(user_id):
        return _display_name(profile.get("nickname"))
    return ""


async def member_name_variables(
    bot: Bot, messages: MessageConfig, key: str, user_id: int, *, group_id: int | None = None,
    values: Mapping[str, MessageValue] | None = None,
) -> dict[str, str]:
    member_name = ""
    if messages.uses_fields(key, "member_name", "member_display", values=values):
        member_name = await lookup_member_name(bot, group_id, user_id)
    return {
        "member_name": member_name,
        "member_display": f"{member_name}（{user_id}）" if member_name else str(user_id),
    }


def is_target_group(event: Event) -> bool:
    return isinstance(event, GroupMessageEvent) and event.group_id == TARGET_GROUP_ID


group_rule = Rule(is_target_group)
