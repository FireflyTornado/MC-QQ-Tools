import asyncio
import sqlite3
import time
from collections import OrderedDict
from contextlib import suppress
from typing import Literal

from nonebot import get_driver, logger, on_command, on_type
from nonebot.adapters.onebot.v11 import (
    Bot,
    GroupMessageEvent,
    GroupIncreaseNoticeEvent,
    GroupDecreaseNoticeEvent,
    Message,
    MessageSegment,
)
from nonebot.adapters.onebot.v11.exception import OneBotV11AdapterException
from nonebot.permission import SUPERUSER

import message_config
from binding_service import (
    DepartureCheck, handle_departure, membership_audit_snapshot, reconcile_checked_bindings, revoke_checked_bindings,
)
from qq_group import TARGET_GROUP_ID, group_rule
from runtime_status import record_outcome

driver = get_driver()
GROUP_ID = str(TARGET_GROUP_ID)
MEMBER_CHECK_TIMEOUT = 20
MEMBER_CHECK_RETRY_DELAY = 5
RECENT_DEPARTURE_LIMIT = 1024
reconcile_lock = asyncio.Lock()
membership_write_lock = asyncio.Lock()
membership_generation = 0
pending_checks_task: asyncio.Task[None] | None = None
DepartureKey = tuple[int, int, int, str]
recent_departures: OrderedDict[DepartureKey, Literal["processing", "handled", "failed"]] = OrderedDict()
pending_departures: OrderedDict[DepartureKey, GroupDecreaseNoticeEvent] = OrderedDict()
bots_outside_group: set[str] = set()


async def send_member_notice(bot: Bot, message: Message) -> None:
    try:
        async with asyncio.timeout(5):
            await bot.send_group_msg(group_id=TARGET_GROUP_ID, message=message)
    except Exception as exc:
        logger.warning("[MC-QQ-AUTH] 群成员提示发送失败（{}）", type(exc).__name__)


# 监听成员入群
member_join = on_type(
    GroupIncreaseNoticeEvent,
    priority=10,
    block=False,
)


@member_join.handle()
async def handle_member_join(
    bot: Bot,
    event: GroupIncreaseNoticeEvent,
):
    global membership_generation
    if event.group_id != TARGET_GROUP_ID:
        return

    membership_generation += 1
    if event.user_id == event.self_id:
        bots_outside_group.discard(str(event.self_id))
        return
    logger.info(
        "[MC-QQ-AUTH] 检测到目标群成员加入事件"
    )
    if message_config.MESSAGES.enabled("join"):
        message = message_config.MESSAGES.render("events.join.message", member=MessageSegment.at(event.user_id))
        await send_member_notice(bot, message)


# 监听成员退群
member_leave = on_type(
    GroupDecreaseNoticeEvent,
    priority=10,
    block=False,
)


@member_leave.handle()
async def handle_member_leave(
    bot: Bot,
    event: GroupDecreaseNoticeEvent,
):
    global membership_generation
    if event.group_id != TARGET_GROUP_ID:
        return

    if event.sub_type == "kick_me" or event.user_id == event.self_id:
        membership_generation += 1
        bots_outside_group.add(str(event.self_id))
        logger.error("[MC-QQ-AUTH] 机器人已离开目标群，群成员授权对账不可用")
        return

    if event.sub_type not in {"leave", "kick"}:
        return

    await process_departure(bot, event)


async def process_departure(bot: Bot, event: GroupDecreaseNoticeEvent, *, retry: bool = False) -> None:
    global membership_generation
    key = (event.user_id, event.time, event.operator_id, event.sub_type)
    if not retry:
        membership_generation += 1
    previous = recent_departures.get(key)
    if previous == "processing":
        return
    recent_departures[key] = "processing"
    recent_departures.move_to_end(key)
    if len(recent_departures) > RECENT_DEPARTURE_LIMIT:
        recent_departures.popitem(last=False)
    reason = "成员主动退群" if event.sub_type == "leave" else "成员被管理员移出群"
    qq_id = str(event.user_id)
    handling: Literal["handled", "failed"] = "failed"
    try:
        async with membership_write_lock:
            result = await asyncio.to_thread(
                handle_departure, GROUP_ID, qq_id, event.time,
                only_same_second=previous == "handled" or key in pending_departures,
            )
        if isinstance(result, DepartureCheck):
            pending_departures[key] = event
            pending_departures.move_to_end(key)
            if len(pending_departures) > RECENT_DEPARTURE_LIMIT:
                pending_departures.popitem(last=False)
            outcome = await verify_same_second_departure(bot, qq_id, result)
        else:
            outcome = "revoked" if result is not None else "no_binding"
        pending_departures.pop(key, None)
    except (sqlite3.Error, UnverifiedMembers, TimeoutError, OneBotV11AdapterException) as exc:
        logger.error("[MC-QQ-AUTH] {}后的撤权暂未确认（{}）", reason, type(exc).__name__)
        outcome = "failed"
    else:
        handling = "handled"
        if outcome == "revoked":
            logger.info("[MC-QQ-AUTH] {}，已撤销绑定", reason)
    finally:
        if recent_departures.get(key) == "processing":
            recent_departures[key] = handling
    if (outcome != "present" and (previous != "handled" or outcome == "revoked")
            and (previous, outcome) != ("failed", "failed") and message_config.MESSAGES.enabled(event.sub_type)):
        await send_member_notice(bot, message_config.MESSAGES.render(f"events.{event.sub_type}.{outcome}"))


class UnverifiedMembers(ValueError):
    pass


def member_ids(rows: object, self_id: str) -> set[str]:
    if not isinstance(rows, list) or not rows:
        raise UnverifiedMembers

    members = set()
    for row in rows:
        if not isinstance(row, dict) or type(row.get("group_id")) is not int or row["group_id"] != TARGET_GROUP_ID:
            raise UnverifiedMembers
        user_id = row.get("user_id")
        if type(user_id) is not int or user_id <= 0 or str(user_id) in members:
            raise UnverifiedMembers
        members.add(str(user_id))
    if self_id not in members:
        raise UnverifiedMembers
    return members


def can_verify_members(bot: Bot) -> bool:
    return driver.bots.get(bot.self_id) is bot and bot.self_id not in bots_outside_group


async def verified_group_members(bot: Bot) -> set[str]:
    # NapCat may serve a cached list while refreshing it. Verify two reads
    # against the independent group details before treating absence as evidence.
    async with asyncio.timeout(MEMBER_CHECK_TIMEOUT):
        first = member_ids(
            await bot.get_group_member_list(group_id=TARGET_GROUP_ID, no_cache=True), bot.self_id,
        )
        detail = await bot.call_api("get_group_detail_info", group_id=TARGET_GROUP_ID)
        second = member_ids(
            await bot.get_group_member_list(group_id=TARGET_GROUP_ID, no_cache=True), bot.self_id,
        )
    count = detail.get("member_count") if isinstance(detail, dict) else None
    if (type(count) is not int or count != len(second) or first != second
            or type(detail.get("group_id")) is not int or detail["group_id"] != TARGET_GROUP_ID):
        raise UnverifiedMembers
    return second


async def verify_same_second_departure(bot: Bot, qq_id: str, check: DepartureCheck) -> str:
    async with reconcile_lock:
        if not can_verify_members(bot):
            raise UnverifiedMembers
        generation = membership_generation
        members = await verified_group_members(bot)
        async with membership_write_lock:
            if membership_generation != generation or not can_verify_members(bot):
                raise UnverifiedMembers
            if qq_id in members:
                return "present"
            deleted = await asyncio.to_thread(revoke_checked_bindings, GROUP_ID, check.version, {qq_id: check.binding_id})
            if deleted is None:
                raise UnverifiedMembers
            return "revoked" if deleted else "no_binding"


async def reconcile_members(bot: Bot, *, only_pending: bool = False) -> int | None:
    async with reconcile_lock:
        if not can_verify_members(bot):
            return None
        generation = membership_generation
        outcome = None
        try:
            audit = await asyncio.to_thread(membership_audit_snapshot, GROUP_ID, only_pending=only_pending)
            if not audit.bindings and not audit.pending:
                if not only_pending:
                    outcome = "OK, 0 revoked"
                return 0

            checked_at = int(time.time())
            members = await verified_group_members(bot)

            # A departure received during the commit must be processed after any restored binding.
            async with membership_write_lock:
                if membership_generation != generation or not can_verify_members(bot):
                    raise UnverifiedMembers
                deleted = await asyncio.to_thread(reconcile_checked_bindings, GROUP_ID, audit, members, checked_at)
            if deleted is None:
                outcome = "Version changed"
                logger.warning("[MC-QQ-AUTH] 对账期间授权版本已变化，本次对账已跳过")
            else:
                outcome = f"OK, {deleted} revoked"
            return deleted
        except (UnverifiedMembers, TimeoutError, OneBotV11AdapterException) as exc:
            outcome = "Roster unverified"
            logger.warning("[MC-QQ-AUTH] 群成员名单无法确认，对账已跳过（{}）", type(exc).__name__)
        except sqlite3.Error as exc:
            outcome = "Database error"
            logger.error("[MC-QQ-AUTH] 授权对账数据库操作失败（{}）", type(exc).__name__)
        finally:
            if outcome is not None:
                record_outcome("reconcile", outcome)
        return None


async def check_pending_bindings() -> None:
    while True:
        await asyncio.sleep(MEMBER_CHECK_RETRY_DELAY)
        bot = next(
            (bot for bot in driver.bots.values() if isinstance(bot, Bot) and bot.self_id not in bots_outside_group),
            None,
        )
        if bot is not None:
            await reconcile_members(bot, only_pending=True)
            departure = next(
                (event for key, event in pending_departures.items() if recent_departures.get(key) != "processing"),
                None,
            )
            if departure is not None:
                await process_departure(bot, departure, retry=True)


@driver.on_startup
async def start_pending_binding_checks() -> None:
    global pending_checks_task
    pending_checks_task = asyncio.create_task(check_pending_bindings())


@driver.on_shutdown
async def stop_pending_binding_checks() -> None:
    global pending_checks_task
    if pending_checks_task is not None:
        pending_checks_task.cancel()
        with suppress(asyncio.CancelledError):
            await pending_checks_task
        pending_checks_task = None


@driver.on_bot_connect
async def reconcile_on_connect(bot: Bot):
    if not isinstance(bot, Bot):
        return
    count = await reconcile_members(bot)
    if count is None and can_verify_members(bot):
        await asyncio.sleep(MEMBER_CHECK_RETRY_DELAY)
        count = await reconcile_members(bot)
    if count is not None:
        logger.info("[MC-QQ-AUTH] OneBot 连接后授权对账完成，已撤销 {} 条绑定", count)


reconcile = on_command(
    "授权对账", aliases={"reconcile"}, rule=group_rule, permission=SUPERUSER, priority=10, block=True,
)


@reconcile.handle()
async def handle_reconcile(bot: Bot, event: GroupMessageEvent):
    if reconcile_lock.locked():
        await reconcile.finish(message_config.MESSAGES.render("commands.reconcile.busy"))
    count = await reconcile_members(bot)
    if count is None:
        await reconcile.finish(message_config.MESSAGES.render("commands.reconcile.failed"))
    await reconcile.finish(message_config.MESSAGES.render("commands.reconcile.success", count=count))
