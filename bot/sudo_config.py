import json
import re
import unicodedata
from dataclasses import dataclass

from app_config import ConfigError, read_switch, require_token

SUDO_TIMEOUT = 30
SUDO_SUBMIT_PATH = "/internal/mc/sudo/submit"
SUDO_FOLLOWUP_PATH = "/internal/mc/sudo/followup"
SUDO_WS_PATH = "/internal/mc/sudo/ws"
SUDO_FOLLOWUP_TIMEOUT = 3600
SUDO_DIRECT_REPLY_TIMEOUT = 30
MAX_FOLLOWUP_FRAMES = 64
MAX_COMMAND_LENGTH = 256
MAX_RESULT_BYTES = 64 * 1024
MAX_RESULT_MESSAGE_BYTES = 512 * 1024


@dataclass(frozen=True)
class SudoOutputSettings:
    line_threshold: int = 10
    line_char_threshold: int = 0
    text_max_chars: int = 1500

    def use_file(self, output: str) -> bool:
        lines = output.splitlines()
        return (
            len(output) > self.text_max_chars
            or len(lines) > self.line_threshold
            or (self.line_char_threshold > 0 and any(len(line) > self.line_char_threshold for line in lines))
        )


def read_sudo_output_settings(config: dict[str, str | None]) -> SudoOutputSettings:
    def threshold(key: str, default: int, minimum: int) -> int:
        value = config.get(key)
        if value is None:
            return default
        try:
            if re.fullmatch(r"[0-9]+", value) and (number := int(value)) >= minimum:
                return number
        except ValueError:
            pass
        raise ConfigError(f"{key} 必须为大于等于 {minimum} 的整数")

    return SudoOutputSettings(
        threshold("MC_SUDO_FILE_LINE_THRESHOLD", 10, 1),
        threshold("MC_SUDO_FILE_LINE_CHAR_THRESHOLD", 0, 0),
        threshold("MC_SUDO_TEXT_MAX_CHARS", 1500, 1),
    )


@dataclass(frozen=True)
class SudoSettings:
    qq_ids: frozenset[str]
    bot_token: str
    mod_token: str


def read_sudo_settings(
    config: dict[str, str | None], *, auth_tokens: tuple[str, ...] = (),
) -> SudoSettings | None:
    enabled = read_switch(config, "MC_SUDO_ENABLED", default=None)
    if enabled is False:
        return None
    raw_ids = config.get("MC_SUDO_QQ_IDS")
    try:
        ids = json.loads(raw_ids) if raw_ids is not None else []
    except (ValueError, RecursionError):
        ids = None
    if not isinstance(ids, list) or any(
        not isinstance(value, str) or re.fullmatch(r"[1-9][0-9]{0,19}", value) is None
        for value in ids
    ):
        raise ConfigError("MC_SUDO_QQ_IDS 必须为 QQ 号字符串的 JSON 数组")
    if not ids:
        if enabled:
            raise ConfigError("启用 /sudo 时，MC_SUDO_QQ_IDS 必须至少包含一个 QQ 号")
        return None
    bot_token = require_token(config, "MC_SUDO_BOT_TOKEN")
    mod_key = "MC_SUDO_MOD_TOKEN"
    legacy_key = "MC_SUDO_PLUGIN_TOKEN"
    legacy_token = config.get(legacy_key)
    if config.get(mod_key) is None and legacy_token is not None:
        mod_token = require_token(config, legacy_key)
        if mod_token == f"REPLACE_WITH_{mod_key}":
            raise ConfigError(f"{legacy_key} 必须填写实际凭据")
    else:
        mod_token = require_token(config, mod_key)
        if legacy_token is not None and legacy_token != mod_token:
            raise ConfigError(f"{mod_key} 与 {legacy_key} 必须使用相同的值")
        if mod_token == f"REPLACE_WITH_{legacy_key}":
            raise ConfigError(f"{mod_key} 必须填写实际凭据")
    if bot_token == mod_token or {bot_token, mod_token} & {config.get("ONEBOT_ACCESS_TOKEN"), *auth_tokens}:
        raise ConfigError("/sudo 凭据必须独立配置")
    return SudoSettings(frozenset(ids), bot_token, mod_token)


def is_single_command(command: str) -> bool:
    return (
        0 < len(command) <= MAX_COMMAND_LENGTH
        and command == command.strip(" ")
        and not any(unicodedata.category(char)[0] == "C" or char in ("\u2028", "\u2029") for char in command)
    )
