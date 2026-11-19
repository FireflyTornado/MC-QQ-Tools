from nonebot import get_driver
from nonebot.adapters import Event
from nonebot.adapters.onebot.v11 import Bot, GroupMessageEvent
from nonebot.rule import Rule


TARGET_GROUP_ID = int(get_driver().config.target_group_id)


def message_variables(bot: Bot) -> dict[str, str]:
    return {"group_id": str(TARGET_GROUP_ID), "bot_qq": bot.self_id}


def is_target_group(event: Event) -> bool:
    return isinstance(event, GroupMessageEvent) and event.group_id == TARGET_GROUP_ID


group_rule = Rule(is_target_group)
