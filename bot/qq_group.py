import asyncio
from unicodedata import category

from nonebot import get_driver
from nonebot.adapters import Event
from nonebot.adapters.onebot.v11 import Bot, GroupMessageEvent
from nonebot.adapters.onebot.v11.exception import OneBotV11AdapterException
from nonebot.rule import Rule


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


async def lookup_member_name(bot: Bot, group_id: int, user_id: int) -> str:
    """Get a group card or QQ nickname for display. Missing names are empty strings."""
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


def is_target_group(event: Event) -> bool:
    return isinstance(event, GroupMessageEvent) and event.group_id == TARGET_GROUP_ID


group_rule = Rule(is_target_group)
