from contextlib import chdir

import nonebot
from nonebot.adapters.onebot.v11 import Adapter

from app_config import (
    BOT_DIR,
    ENV_FILE,
    ConfigError,
    load_config,
    onebot_ws_urls,
    require_token,
    target_group_id,
)
from component_config import check_auth_database, read_components


def init_bot() -> None:
    config = load_config()
    group_id = target_group_id(config)
    ws_urls = onebot_ws_urls(config)
    components = check_auth_database(read_components(config, for_api=False))
    access_token = None
    if config.get("ONEBOT_ACCESS_TOKEN"):
        access_token = require_token(config, "ONEBOT_ACCESS_TOKEN")

    # NoneBot resolves plugin module names and its own Env relative to cwd.
    with chdir(BOT_DIR):
        # NoneBot can log the full config at DEBUG/TRACE during initialization.
        nonebot.logger.disable("nonebot")
        try:
            nonebot.init(
                _env_file=(ENV_FILE,),
                target_group_id=group_id,
                onebot_v11_ws_urls=ws_urls,
                onebot_v11_access_token=access_token,
            )
            driver = nonebot.get_driver()
            if isinstance(driver.config.log_level, str):
                nonebot.logger.level(driver.config.log_level)
            driver.register_adapter(Adapter)
        except (AttributeError, ImportError, KeyError, RuntimeError, ValueError):
            raise ConfigError("NoneBot 初始化失败，请检查 DRIVER、LOG_LEVEL 和 OneBot 配置") from None
        finally:
            nonebot.logger.enable("nonebot")

        auth_enabled = components.auth is not None
        sudo_enabled = False
        if auth_enabled:
            for name in ("qq_events", "qq_bindings", "qq_db"):
                nonebot.load_plugin(f"plugins.{name}")
        if components.sudo is not None and components.sudo_output is not None:
            plugin = nonebot.load_plugin("plugins.qq_sudo")
            if plugin is not None:
                plugin.module.configure(components.sudo, components.sudo_output)
                sudo_enabled = True
        status = nonebot.load_plugin("plugins.status_commands")
        if status is not None:
            status.module.configure(auth=auth_enabled, sudo=sudo_enabled)


init_bot()

if __name__ == "__main__":
    nonebot.run()
