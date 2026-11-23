import logging
import secrets
from dataclasses import dataclass

from app_config import ConfigError, read_switch, require_token
from sudo_config import SudoSettings, read_sudo_settings


logger = logging.getLogger("mc_qq_auth")


@dataclass(frozen=True)
class AuthSettings:
    api_token: str
    notify_token: str


@dataclass(frozen=True)
class ComponentSettings:
    auth: AuthSettings | None
    sudo: SudoSettings | None


def read_auth_settings(config: dict[str, str | None]) -> AuthSettings | None:
    if not read_switch(config, "MC_AUTH_ENABLED", default=True):
        return None
    api_token = require_token(config, "MC_API_TOKEN")
    notify_token = require_token(config, "MC_NOTIFY_TOKEN")
    if secrets.compare_digest(api_token, notify_token):
        raise ConfigError("MC_API_TOKEN 与 MC_NOTIFY_TOKEN 必须使用不同的值")
    return AuthSettings(api_token, notify_token)


def read_components(config: dict[str, str | None]) -> ComponentSettings:
    auth = None
    sudo = None
    auth_error = None
    sudo_error = None
    try:
        auth = read_auth_settings(config)
    except ConfigError as exc:
        auth_error = exc
    try:
        sudo = read_sudo_settings(config, auth_tokens=(auth.api_token, auth.notify_token) if auth else ())
    except ConfigError as exc:
        sudo_error = exc

    if auth is None and sudo is None and (error := auth_error or sudo_error):
        raise error
    if auth_error:
        logger.error("MCQQAuth 已关闭：%s", auth_error)
    if sudo_error:
        logger.error("/sudo 已关闭：%s", sudo_error)
    return ComponentSettings(auth, sudo)


def read_banlist_token(
    config: dict[str, str | None], auth: AuthSettings, sudo: SudoSettings | None,
) -> bytes | None:
    if config.get("MC_BANLIST_TOKEN"):
        try:
            token = require_token(config, "MC_BANLIST_TOKEN")
            other_tokens = {auth.api_token, auth.notify_token, config.get("ONEBOT_ACCESS_TOKEN")}
            if sudo is not None:
                other_tokens.update((sudo.bot_token, sudo.plugin_token))
            if token in other_tokens:
                raise ConfigError("MC_BANLIST_TOKEN 必须使用独立凭据")
            return token.encode("ascii")
        except ConfigError as exc:
            logger.error("封禁名单上报已关闭：%s", exc)
    return None
