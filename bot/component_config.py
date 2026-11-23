import logging
import secrets
from dataclasses import dataclass, replace

from app_config import ConfigError, read_switch, require_token
from sudo_config import (
    LEGACY_MOD_TOKEN_KEY,
    MOD_TOKEN_KEY,
    SudoOutputSettings,
    SudoSettings,
    read_sudo_output_settings,
    read_sudo_settings,
)


logger = logging.getLogger("mc_qq_tools")


@dataclass(frozen=True)
class AuthSettings:
    notify_token: str
    api_token: str | None


@dataclass(frozen=True)
class ComponentSettings:
    auth: AuthSettings | None
    sudo: SudoSettings | None
    sudo_output: SudoOutputSettings | None = None


def read_auth_settings(config: dict[str, str | None], *, require_api_token: bool) -> AuthSettings | None:
    if not read_switch(config, "MC_AUTH_ENABLED", default=True):
        return None
    api_token = None
    if require_api_token or config.get("MC_API_TOKEN") is not None:
        api_token = require_token(config, "MC_API_TOKEN")
    notify_token = require_token(config, "MC_NOTIFY_TOKEN")
    if api_token is not None and secrets.compare_digest(api_token, notify_token):
        raise ConfigError("MC_API_TOKEN 与 MC_NOTIFY_TOKEN 必须使用不同的值")
    return AuthSettings(notify_token=notify_token, api_token=api_token)


def read_components(config: dict[str, str | None], *, for_api: bool = True) -> ComponentSettings:
    auth = None
    sudo = None
    auth_error = None
    sudo_error = None
    sudo_output = None
    try:
        auth = read_auth_settings(config, require_api_token=for_api)
    except ConfigError as exc:
        auth_error = exc
    try:
        sudo = read_sudo_settings(config, require_mod_token=for_api)
        if sudo is not None:
            sudo_output = read_sudo_output_settings(config)
    except ConfigError as exc:
        sudo = None
        sudo_error = exc

    if auth is None and sudo is None and (error := auth_error or sudo_error):
        raise error
    if auth_error:
        logger.error("MCQQAuth 已关闭：%s", auth_error)
    if sudo_error:
        logger.error("/sudo 已关闭：%s", sudo_error)
    return ComponentSettings(auth, sudo, sudo_output)


def check_auth_database(components: ComponentSettings) -> ComponentSettings:
    if components.auth is None:
        return components

    import sqlite3

    from database import check_schema_version

    try:
        check_schema_version()
    except (OSError, sqlite3.Error) as exc:
        message = "MCQQAuth 数据库不可用或版本不符，请在停写并备份后执行 bot/migrate.py。"
        if components.sudo is None:
            raise ConfigError(message) from None
        logger.error("MCQQAuth 已关闭：%s（%s）", message, type(exc).__name__)
        return replace(components, auth=None)
    return components


def read_banlist_token(config: dict[str, str | None], auth: AuthSettings) -> bytes | None:
    if config.get("MC_BANLIST_TOKEN"):
        try:
            token = require_token(config, "MC_BANLIST_TOKEN")
            other_tokens = {
                auth.api_token,
                auth.notify_token,
                config.get("ONEBOT_ACCESS_TOKEN"),
                config.get("MC_SUDO_BOT_TOKEN"),
                config.get(MOD_TOKEN_KEY),
                config.get(LEGACY_MOD_TOKEN_KEY),
            }
            if token in other_tokens:
                raise ConfigError("MC_BANLIST_TOKEN 必须使用独立凭据")
            return token.encode("ascii")
        except ConfigError as exc:
            logger.error("封禁名单上报已关闭：%s", exc)
    return None
