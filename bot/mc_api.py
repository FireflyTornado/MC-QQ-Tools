from fastapi import FastAPI

from api_security import verify_bearer
from app_config import load_config, target_group_id
from component_config import read_banlist_token, read_components


def create_app(config: dict[str, str | None]) -> FastAPI:
    group_id = str(target_group_id(config))
    components = read_components(config)
    app = FastAPI(
        title="MC-QQ-Tools API",
        docs_url=None,
        redoc_url=None,
        openapi_url=None,
    )

    auth = None
    if components.auth is not None:
        from auth_api import AuthApi

        auth = AuthApi(components.auth, group_id, read_banlist_token(config, components.auth, components.sudo))
        app.include_router(auth.router)
    app.state.auth = auth
    sudo = None
    if components.sudo is not None:
        from sudo_api import sudo_router

        sudo = sudo_router(components.sudo, group_id, verify_bearer)
        app.include_router(sudo)

    @app.get("/internal/health")
    async def health():
        return {
            "status": "ok",
            "auth_enabled": auth is not None,
            "minecraft_connections": len(auth.connections) if auth is not None else 0,
            "sudo_connected": sudo.connected if sudo is not None else None,
        }

    return app


app = create_app(load_config())
