import re
from collections.abc import Mapping
from dataclasses import dataclass
from pathlib import Path
from types import MappingProxyType

import yaml
from nonebot import logger
from nonebot.adapters.onebot.v11 import Message, MessageSegment

from app_config import BOT_DIR


MESSAGE_FILE = BOT_DIR / "messages.yml"
MAX_FILE_BYTES = 64 * 1024
MAX_TEXT_LENGTH = 1000

DEFAULTS = MappingProxyType({
    "events.join.enabled": False,
    "events.join.message": (
        "{member} 欢迎加入！请用 /绑定 游戏ID 或 /bind 游戏ID 绑定 Minecraft 游戏 ID。\n"
        "发送 /帮助 或 /help 查看指令。"
    ),
    "events.leave.enabled": False,
    "events.leave.revoked": "成员已退出群聊。Minecraft 授权已自动撤销。",
    "events.leave.retained": "成员已退出群聊。Minecraft 绑定仍保留。",
    "events.leave.no_binding": "成员已退出群聊。",
    "events.leave.failed": "成员已退出群聊。Minecraft 授权撤销暂未确认。",
    "events.kick.enabled": False,
    "events.kick.revoked": "成员已被移出群聊。Minecraft 授权已自动撤销。",
    "events.kick.retained": "成员已被移出群聊。Minecraft 绑定仍保留。",
    "events.kick.no_binding": "成员已被移出群聊。",
    "events.kick.failed": "成员已被移出群聊。Minecraft 授权撤销暂未确认。",
    "commands.bind.usage": "用法：/绑定 Minecraft玩家名",
    "commands.bind.success": "绑定成功！\nMinecraft ID：{mc_name}\n你的绑定记录已保存。",
    "commands.bind.invalid_name": "游戏 ID 格式不正确。\n请填写由英文字母、数字或下划线组成的游戏 ID，最多 16 位。",
    "commands.bind.already_bound": "你已经绑定了游戏 ID：{mc_name}\n如需更换，请先使用 /解绑",
    "commands.bind.name_taken": "该游戏 ID 已经被绑定，无法重复绑定。",
    "commands.bind.banned_name": "游戏 ID {mc_name} 已被 Minecraft 服务器封禁，暂时无法绑定。",
    "commands.bind.error": "绑定服务发生错误，请稍后重试",
    "commands.unbind.success": "解绑成功！\n原 Minecraft ID：{mc_name}\n绑定记录已删除。",
    "commands.unbind.not_bound": "你当前没有有效的绑定记录。",
    "commands.unbind.frozen": "你绑定的 Minecraft ID {mc_name} 处于封禁冻结状态，暂时无法解绑。",
    "commands.unbind.pending": "你绑定的 Minecraft ID {mc_name} 已解封，正在核实群成员身份，暂时无法解绑。",
    "commands.unbind.error": "解绑失败，请稍后重试",
    "commands.binding.success": "当前绑定的 Minecraft ID：{mc_name}",
    "commands.binding.frozen": "当前绑定的 Minecraft ID：{mc_name}\n状态：封禁冻结。",
    "commands.binding.pending": "当前绑定的 Minecraft ID：{mc_name}\n状态：已解封，正在核实群成员身份。",
    "commands.binding.not_bound": "你还没有绑定 Minecraft 游戏 ID。\n请使用 /绑定 玩家名",
    "commands.binding.error": "查询失败，请稍后重试",
    "commands.ping.success": "[MC-QQ-Auth] Ping\nFastAPI Health: HTTP {status_code}\nLatency: {latency_ms} ms",
    "commands.ping.timeout": "[MC-QQ-Auth] Ping\nFastAPI Health: Timeout\nLatency: {latency_ms} ms",
    "commands.ping.connection_error": "[MC-QQ-Auth] Ping\nFastAPI Health: Connection failed\nLatency: {latency_ms} ms",
    "commands.ping.error": "[MC-QQ-Auth] Ping\nFastAPI Health: Invalid HTTP response\nLatency: {latency_ms} ms",
    "commands.reconcile.success": "授权对账完成，已撤销 {count} 条离群绑定。",
    "commands.reconcile.failed": "授权对账未完成，请检查群成员名单和服务日志。",
    "commands.reconcile.busy": "授权对账正在执行，请稍后重试。",
    "commands.db.usage": (
        "/db add <QQ号> <游戏名> · 添加绑定\n"
        "/db rm <QQ号或游戏名> · 删除有效绑定\n"
        "/db get <QQ号或游戏名> · 查询绑定\n"
        "纯数字有歧义时，用 qq: 或 mc: 指定类型。"
    ),
    "commands.db.add_usage": "用法：/db add <QQ号> <游戏名>",
    "commands.db.rm_usage": "用法：/db rm <QQ号或游戏名>，可加 qq: 或 mc: 前缀。",
    "commands.db.get_usage": "用法：/db get <QQ号或游戏名>，可加 qq: 或 mc: 前缀。",
    "commands.db.invalid_qq": "QQ 号格式不正确，请输入 QQ 号的数字。",
    "commands.db.invalid_name": "游戏名须由 1–16 位英文字母、数字或下划线组成。",
    "commands.db.members_unverified": "暂时无法核实目标群成员，请稍后重试。",
    "commands.db.not_member": "QQ {member_qq} 当前不在目标群，无法添加绑定。",
    "commands.db.success": "已添加绑定：{member_qq} ↔ {mc_name}〔有效〕",
    "commands.db.already_same": "已绑定：{member_qq} ↔ {mc_name}",
    "commands.db.already_bound": "QQ {member_qq} 已有绑定：{mc_name}。",
    "commands.db.name_taken": "游戏名 {mc_name} 已被其他绑定占用。",
    "commands.db.banned_name": "游戏名 {mc_name} 已被游戏服封禁，暂时无法绑定。",
    "commands.db.active": "{member_display} ↔ {mc_name}〔有效〕",
    "commands.db.frozen": "{member_display} ↔ {mc_name}〔封禁冻结〕",
    "commands.db.pending": "{member_display} ↔ {mc_name}〔待核实群成员〕",
    "commands.db.removed": "已删除有效绑定：{member_qq} ↔ {mc_name}",
    "commands.db.rm_frozen": "{member_qq} ↔ {mc_name} 因游戏服封禁被冻结。请先在游戏服解封，待绑定恢复后重试。",
    "commands.db.rm_pending": "{member_qq} ↔ {mc_name} 正在等待群成员核实，可用 /授权对账 触发检查。",
    "commands.db.ambiguous": "按 QQ 号和游戏名查到了两条不同绑定，请用 qq: 或 mc: 指定类型。",
    "commands.db.not_bound": "未找到绑定。",
    "commands.db.error": "绑定数据库操作失败，请稍后重试。",
    "commands.help.message": (
        "[MC-QQ-Auth] 指令帮助\n"
        "/绑定 游戏ID 或 /bind 游戏ID · 绑定 Minecraft 游戏 ID\n"
        "/我的绑定 或 /binding · 查询自己的绑定\n"
        "/解绑 或 /unbind · 撤销自己的绑定\n"
        "/认证测试 或 /ping · 检查授权 API\n"
        "/帮助 或 /help · 显示本帮助\n"
        "超级用户专用\n"
        "/信息 或 /info · 查看状态图片\n"
        "/授权对账 或 /reconcile · 核对群成员与授权\n"
        "/db · 手动添加、删除或查询绑定"
    ),
    "commands.info.busy": "状态图正在生成，请稍后重试。",
    "commands.info.error": "状态图暂不可用，请稍后重试。",
    "info.title": "MC-QQ-Auth",
    "info.footer": "MC-QQ-Auth",
    "info.labels.bot": "Bot",
    "info.labels.onebot": "OneBot",
    "info.labels.api": "FastAPI",
    "info.labels.database": "SQLite",
    "info.labels.system_uptime": "系统",
    "info.labels.bot_uptime": "NoneBot2",
    "info.labels.connection_uptime": "OneBot 连接",
    "info.labels.os": "操作系统",
    "info.labels.python": "Python",
    "info.labels.cpu_cores": "逻辑核心",
    "info.labels.memory": "内存",
    "info.labels.disk": "磁盘",
    "info.labels.auth_version": "Authorization version",
    "info.labels.bindings": "有效绑定",
    "info.labels.bans": "封禁记录",
    "info.labels.last_reconcile": "Last member reconciliation",
    "info.labels.last_notice": "Last authorization notice",
})
SECTIONS = {key.rsplit(".", depth)[0] for key in DEFAULTS for depth in range(1, key.count(".") + 1)}

COMMON_FIELDS = {"group_id", "bot_qq"}
BUSINESS_FIELDS = {
    "events.join.message": {"member", "member_qq"},
    "events.leave.revoked": {"member_qq", "mc_name"},
    "events.leave.retained": {"member_qq", "mc_name"},
    "events.leave.no_binding": {"member_qq"},
    "events.leave.failed": {"member_qq"},
    "events.kick.revoked": {"member_qq", "mc_name"},
    "events.kick.retained": {"member_qq", "mc_name"},
    "events.kick.no_binding": {"member_qq"},
    "events.kick.failed": {"member_qq"},
    "commands.bind.success": {"mc_name"},
    "commands.bind.already_bound": {"mc_name"},
    "commands.bind.banned_name": {"mc_name"},
    "commands.unbind.success": {"mc_name"},
    "commands.unbind.frozen": {"mc_name"},
    "commands.unbind.pending": {"mc_name"},
    "commands.binding.success": {"mc_name"},
    "commands.binding.frozen": {"mc_name"},
    "commands.binding.pending": {"mc_name"},
    "commands.ping.success": {"status_code", "latency_ms"},
    "commands.ping.timeout": {"latency_ms"},
    "commands.ping.connection_error": {"latency_ms"},
    "commands.ping.error": {"latency_ms"},
    "commands.reconcile.success": {"count"},
    "commands.db.not_member": {"member_qq"},
    "commands.db.name_taken": {"mc_name"},
    "commands.db.banned_name": {"mc_name"},
    **{
        f"commands.db.{status}": {"member_qq", "mc_name", "member_name", "member_display"}
        for status in ("active", "frozen", "pending")
    },
    **{
        f"commands.db.{status}": {"member_qq", "mc_name"}
        for status in ("success", "already_same", "already_bound", "removed", "rm_frozen", "rm_pending")
    },
}
TOKENS = re.compile(r"(\{\{|\}\}|\{[a-z_]+\}|[{}])")
UNSAFE_TEXT = re.compile(r"[\x00-\x08\x0b\x0c\x0e-\x1f\x7f-\x9f\ud800-\udfff]")


@dataclass(frozen=True)
class MessageConfig:
    values: Mapping[str, str | bool]

    def enabled(self, event: str) -> bool:
        return self.values[f"events.{event}.enabled"] is True

    def text(self, key: str) -> str:
        value = self.values[key]
        assert isinstance(value, str)
        return value

    def uses_fields(self, key: str, *fields: str) -> bool:
        return any(token[1:-1] in fields for token in TOKENS.split(self.text(key))[1::2])

    def info_text(self, key: str, **values: str | int | None) -> str:
        return self.render(key, **values).extract_plain_text()

    def render(self, key: str, **values: str | int | MessageSegment | None) -> Message:
        template = self.text(key)
        message, length = _render(template, values)
        if length > MAX_TEXT_LENGTH:
            logger.warning("[MC-QQ-AUTH] 消息渲染后过长，使用默认文案（{}）", key)
            message, _ = _render(DEFAULTS[key], values)
        return message


def _render(
    template: str, values: Mapping[str, str | int | MessageSegment | None],
) -> tuple[Message, int]:
    message = Message()
    text: list[str] = []
    length = 0
    for index, part in enumerate(TOKENS.split(template)):
        if index % 2:
            if part in ("{{", "}}"):
                part = part[0]
            else:
                value = values.get(part[1:-1])
                if isinstance(value, MessageSegment):
                    if text:
                        message.append(MessageSegment.text("".join(text)))
                        text.clear()
                    message.append(value)
                    length += len(str(value))
                    continue
                part = "—" if value is None else UNSAFE_TEXT.sub("�", str(value))[:128]
        if part:
            text.append(part)
            length += len(part)
    if text:
        message.append(MessageSegment.text("".join(text)))
    return message, length


def _apply_overrides(raw: object, values: dict[str, str | bool], prefix: str = "") -> None:
    if not isinstance(raw, dict):
        logger.warning("[MC-QQ-AUTH] 消息配置节应为映射，使用默认值（{}）", prefix or "根节点")
        return
    for name, value in raw.items():
        if not isinstance(name, str) or "." in name:
            key = ""
        else:
            key = f"{prefix}.{name}" if prefix else name
        if key in SECTIONS:
            _apply_overrides(value, values, key)
        elif key not in DEFAULTS:
            logger.warning("[MC-QQ-AUTH] 忽略未知消息配置项（{}）", prefix or "根节点")
        elif isinstance(DEFAULTS[key], bool):
            if type(value) is bool:
                values[key] = value
            else:
                logger.warning("[MC-QQ-AUTH] 消息通知开关应为布尔值，使用默认值（{}）", key)
        elif not isinstance(value, str) or not value.strip() or len(value) > MAX_TEXT_LENGTH or UNSAFE_TEXT.search(value):
            logger.warning("[MC-QQ-AUTH] 消息文案无效或过长，使用默认值（{}）", key)
        elif any(
            token not in ("{{", "}}")
            and token[1:-1] not in COMMON_FIELDS
            and token[1:-1] not in BUSINESS_FIELDS.get(key, ())
            for token in TOKENS.split(value)[1::2]
        ):
            logger.warning("[MC-QQ-AUTH] 消息占位符无效，使用默认值（{}）", key)
        else:
            values[key] = value


def load_messages(path: Path = MESSAGE_FILE) -> MessageConfig:
    values = dict(DEFAULTS)
    try:
        with path.open("rb") as file:
            data = file.read(MAX_FILE_BYTES + 1)
        if len(data) > MAX_FILE_BYTES:
            raise ValueError
        raw = yaml.safe_load(data.decode("utf-8-sig"))
    except FileNotFoundError:
        pass
    except (OSError, ValueError, OverflowError, RecursionError, yaml.YAMLError):
        # YAML errors can quote file content, so never log the exception itself.
        logger.error("[MC-QQ-AUTH] 无法读取或解析 messages.yml，使用默认配置")
    else:
        _apply_overrides(raw, values)
    return MessageConfig(MappingProxyType(values))


# NoneBot loads the plugins at startup. All handlers share this validated snapshot.
MESSAGES = load_messages()
