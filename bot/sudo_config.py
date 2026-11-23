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
MOD_TOKEN_KEY = "MC_SUDO_MOD_TOKEN"
LEGACY_MOD_TOKEN_KEY = "MC_SUDO_PLUGIN_TOKEN"


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
    mod_token: str | None = None


def _read_mod_token(config: dict[str, str | None]) -> str:
    legacy_token = config.get(LEGACY_MOD_TOKEN_KEY)
    if config.get(MOD_TOKEN_KEY) is None and legacy_token is not None:
        token = require_token(config, LEGACY_MOD_TOKEN_KEY)
        if token == f"REPLACE_WITH_{MOD_TOKEN_KEY}":
            raise ConfigError(f"{LEGACY_MOD_TOKEN_KEY} 必须填写实际凭据")
        return token
    token = require_token(config, MOD_TOKEN_KEY)
    if legacy_token is not None and legacy_token != token:
        raise ConfigError(f"{MOD_TOKEN_KEY} 与 {LEGACY_MOD_TOKEN_KEY} 必须使用相同的值")
    if token == f"REPLACE_WITH_{LEGACY_MOD_TOKEN_KEY}":
        raise ConfigError(f"{MOD_TOKEN_KEY} 必须填写实际凭据")
    return token


def read_sudo_qq_ids(config: dict[str, str | None]) -> frozenset[str]:
    enabled = read_switch(config, "MC_SUDO_ENABLED", default=None)
    if enabled is False:
        return frozenset()
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
    if enabled and not ids:
        raise ConfigError("启用 /sudo 时，MC_SUDO_QQ_IDS 必须至少包含一个 QQ 号")
    return frozenset(ids)


def read_sudo_settings(config: dict[str, str | None], *, require_mod_token: bool = True) -> SudoSettings | None:
    qq_ids = read_sudo_qq_ids(config)
    if not qq_ids:
        return None
    bot_token = require_token(config, "MC_SUDO_BOT_TOKEN")
    configured_mod_tokens = {config.get(MOD_TOKEN_KEY), config.get(LEGACY_MOD_TOKEN_KEY)}
    mod_token = _read_mod_token(config) if require_mod_token or configured_mod_tokens != {None} else None
    reserved_tokens = {
        token for key in ("ONEBOT_ACCESS_TOKEN", "MC_API_TOKEN", "MC_NOTIFY_TOKEN", "MC_BANLIST_TOKEN")
        if (token := config.get(key))
    }
    if bot_token in configured_mod_tokens or ({bot_token} | configured_mod_tokens) & reserved_tokens:
        raise ConfigError("/sudo 凭据必须独立配置")
    return SudoSettings(qq_ids, bot_token, mod_token)


def is_single_command(command: str) -> bool:
    return (
        0 < len(command) <= MAX_COMMAND_LENGTH
        and command == command.strip(" ")
        and not any(unicodedata.category(char)[0] == "C" or char in ("\u2028", "\u2029") for char in command)
    )
