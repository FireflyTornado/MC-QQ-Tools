import asyncio
import logging
import sqlite3
from contextlib import asynccontextmanager, closing, suppress
from dataclasses import dataclass, field
from typing import Annotated

from fastapi import (
    APIRouter, BackgroundTasks, Depends, Header, HTTPException, Request, Response, WebSocket, WebSocketDisconnect,
)
from pydantic import BaseModel, ConfigDict, Field
from starlette.requests import ClientDisconnect

import database
from api_security import require_bearer, verify_bearer
from banlist_sync import GenerationClaimed, UploadConflict, read_generation, store_upload
from component_config import AuthSettings


WS_SEND_TIMEOUT = 3
WS_CLOSE_TIMEOUT = 3
RECONCILE_INTERVAL = 60
BANLIST_PATH = "/internal/mc/banlist"
MAX_BANLIST_BYTES = 2 * 1024 * 1024
logger = logging.getLogger("mc_qq_tools.auth")


UploadNumber = Annotated[int, Field(strict=True, ge=1, le=database.MAX_AUTH_VERSION)]
Expiration = Annotated[int, Field(strict=True, ge=0, le=database.MAX_AUTH_VERSION)]


class BanlistProfile(BaseModel):
    model_config = ConfigDict(extra="forbid")

    name: str | None
    expires_at: Expiration | None


class BanlistUpload(BaseModel):
    model_config = ConfigDict(extra="forbid")

    generation: UploadNumber
    client_id: Annotated[str, Field(pattern=r"^[0-9a-f]{32}$")]
    sequence: UploadNumber
    entries: dict[str, Expiration | None]
    profiles: dict[str, BanlistProfile] | None = None


@dataclass
class BroadcastConnection:
    send_lock: asyncio.Lock = field(default_factory=asyncio.Lock)
    failed: asyncio.Event = field(default_factory=asyncio.Event)


class AuthApi:
    def __init__(self, settings: AuthSettings, target_group: str, banlist_token: bytes | None):
        self.api_token = settings.api_token.encode("ascii")
        self.notify_token = settings.notify_token.encode("ascii")
        self.banlist_token = banlist_token
        self.target_group = target_group
        self.db_path = database.DB_PATH
        # Routes close their own sockets. Broadcasts only mark failed connections.
        self.connections: dict[WebSocket, BroadcastConnection] = {}
        self.broadcast_lock = asyncio.Lock()
        self.last_broadcast_version: int | None = None
        self.router = APIRouter(lifespan=self.lifespan)
        self.router.add_api_route(
            "/internal/mc/snapshot", self.get_snapshot, methods=["GET"], dependencies=[Depends(self.require_token)],
        )
        self.router.add_api_route(
            BANLIST_PATH, self.get_banlist_generation, methods=["GET"], dependencies=[Depends(self.require_banlist_token)],
        )
        self.router.add_api_route(
            BANLIST_PATH, self.upload_banlist, methods=["PUT"], dependencies=[Depends(self.require_banlist_token)],
        )
        self.router.add_api_route("/internal/mc/notify", self.notify_auth_change, methods=["POST"])
        self.router.add_api_websocket_route("/internal/mc/ws", self.mc_websocket)

    def require_token(self, authorization: list[str] | None = Header(default=None)) -> None:
        require_bearer(authorization, self.api_token)

    def require_banlist_token(self, authorization: list[str] | None = Header(default=None)) -> None:
        if self.banlist_token is None:
            raise HTTPException(status_code=404, detail="Banlist uploads disabled")
        require_bearer(authorization, self.banlist_token)

    @asynccontextmanager
    async def lifespan(self, _app):
        task = asyncio.create_task(self.reconcile_versions())
        try:
            yield
        finally:
            task.cancel()
            with suppress(asyncio.CancelledError):
                await task

    def get_snapshot(self, response: Response):
        """读取当前有效授权名单，不修改数据库。"""

        response.headers["Cache-Control"] = "no-store"

        # 以 SQLite 只读模式打开现有数据库
        db_uri = f"file:{self.db_path}?mode=ro"

        try:
            with closing(
                sqlite3.connect(
                    db_uri,
                    uri=True,
                    timeout=5,
                )
            ) as conn:

                # 同一事务内读取版本号和绑定数据
                conn.execute("BEGIN")

                row = conn.execute(
                    "SELECT version FROM auth_state WHERE id = 1"
                ).fetchone()

                if row is None:
                    raise RuntimeError("授权版本记录不存在")

                version = row[0]

                rows = conn.execute(
                    """
                    SELECT mc_name_key
                    FROM offline_bindings
                    WHERE group_id = ?
                      AND status = 'ACTIVE'
                    ORDER BY mc_name_key
                    """,
                    (self.target_group,),
                ).fetchall()

                active_names = [row[0] for row in rows]

                return {
                    "version": version,
                    "active_names": active_names,
                }

        except sqlite3.Error:
            raise HTTPException(
                status_code=503,
                detail="Authorization database unavailable",
            )

    def read_auth_version(self) -> int:
        """从 SQLite 读取最新授权版本。"""

        uri = f"file:{self.db_path}?mode=ro"

        with closing(
            sqlite3.connect(uri, uri=True, timeout=5)
        ) as conn:
            row = conn.execute(
                "SELECT version FROM auth_state WHERE id = 1"
            ).fetchone()

            if row is None or type(row[0]) is not int or row[0] < 0:
                raise RuntimeError("授权版本记录无效")
            return row[0]

    async def send_update(self, websocket: WebSocket, connection: BroadcastConnection, message: dict) -> bool:
        try:
            async with asyncio.timeout(WS_SEND_TIMEOUT), connection.send_lock:
                if websocket not in self.connections:
                    return False
                await websocket.send_json(message)
            return True
        except Exception as exc:
            self.connections.pop(websocket, None)
            connection.failed.set()
            logger.warning("授权 WebSocket 广播失败，正在关闭连接（%s）", type(exc).__name__)
            return False

    async def broadcast_latest(self):
        """串行发布递增版本，每个版本并发向各连接发送。"""
        async with self.broadcast_lock:
            version = await asyncio.to_thread(self.read_auth_version)
            if version == self.last_broadcast_version:
                return version, 0
            if self.last_broadcast_version is not None and version < self.last_broadcast_version:
                raise RuntimeError("数据库授权版本回退")

            message = {"type": "auth_updated", "version": version}
            delivered = await asyncio.gather(*(
                self.send_update(websocket, connection, message)
                for websocket, connection in tuple(self.connections.items())
            ))
            self.last_broadcast_version = version
            return version, sum(delivered)

    def get_banlist_generation(self, response: Response):
        response.headers["Cache-Control"] = "no-store"
        try:
            return {"generation": read_generation(self.db_path)}
        except sqlite3.Error:
            logger.exception("无法读取封禁名单同步会话")
            raise HTTPException(status_code=503, detail="Banlist database unavailable") from None

    async def upload_banlist(self, request: Request, background: BackgroundTasks, response: Response):
        response.headers["Cache-Control"] = "no-store"
        body = bytearray()
        try:
            async for chunk in request.stream():
                if len(body) + len(chunk) > MAX_BANLIST_BYTES:
                    raise HTTPException(status_code=413, detail="Banlist snapshot is too large")
                body.extend(chunk)
            upload = BanlistUpload.model_validate_json(body)
            frozen = await asyncio.to_thread(store_upload, self.db_path, **upload.model_dump())
        except (ValueError, ClientDisconnect):
            raise HTTPException(status_code=422, detail="Invalid banlist snapshot") from None
        except GenerationClaimed:
            raise HTTPException(
                status_code=409, detail="Banlist upload cursor conflict",
                headers={"X-MCQQ-Banlist-Conflict": "generation-claimed"},
            ) from None
        except UploadConflict:
            raise HTTPException(status_code=409, detail="Banlist upload cursor conflict") from None
        except sqlite3.Error:
            logger.exception("封禁名单写入失败")
            raise HTTPException(status_code=503, detail="Banlist database unavailable") from None

        if frozen:
            background.add_task(self.broadcast_banlist_update)
        return {"generation": upload.generation, "sequence": upload.sequence}

    async def broadcast_banlist_update(self) -> None:
        try:
            await self.broadcast_latest()
        except (sqlite3.Error, RuntimeError):
            logger.exception("封禁授权更新通知失败，将等待授权版本补偿检查")

    async def notify_auth_change(
        self,
        authorization: list[str] | None = Header(default=None),
    ):
        require_bearer(authorization, self.notify_token)

        try:
            version, delivered = await self.broadcast_latest()
        except (sqlite3.Error, RuntimeError):
            logger.exception("授权版本读取或推送失败")
            raise HTTPException(
                status_code=503,
                detail="Authorization update unavailable",
            )

        return {
            "success": True,
            "version": version,
            "delivered": delivered,
        }

    async def mc_websocket(self, websocket: WebSocket):
        if not verify_bearer(websocket.headers.getlist("authorization"), self.api_token):
            await websocket.close(code=1008)
            return

        await websocket.accept()

        connection = BroadcastConnection()

        try:
            # Register before the greeting so the initial snapshot and broadcasts overlap.
            async with connection.send_lock:
                self.connections[websocket] = connection
                try:
                    async with asyncio.timeout(WS_SEND_TIMEOUT):
                        await websocket.send_json({
                            "type": "connected",
                            "message": "Minecraft authentication connected",
                        })
                except Exception:
                    self.connections.pop(websocket, None)
                    await close_failed_websocket(websocket)
                    return

            disconnected = asyncio.create_task(wait_for_disconnect(websocket))
            failed = asyncio.create_task(connection.failed.wait())
            try:
                done, _ = await asyncio.wait((disconnected, failed), return_when=asyncio.FIRST_COMPLETED)
                if disconnected in done:
                    disconnected.result()
                else:
                    await close_failed_websocket(websocket)
            finally:
                disconnected.cancel()
                failed.cancel()
                await asyncio.gather(disconnected, failed, return_exceptions=True)
        finally:
            self.connections.pop(websocket, None)

    async def reconcile_versions(self):
        while True:
            await asyncio.sleep(RECONCILE_INTERVAL)

            try:
                await self.broadcast_latest()
            except Exception:
                logger.exception("授权版本补偿检查失败")


async def wait_for_disconnect(websocket: WebSocket) -> None:
    while (await websocket.receive())["type"] != "websocket.disconnect":
        pass


async def close_failed_websocket(websocket: WebSocket) -> None:
    try:
        async with asyncio.timeout(WS_CLOSE_TIMEOUT):
            await websocket.close(code=1011)
    except WebSocketDisconnect:
        pass
    except Exception as exc:
        # The deployed Uvicorn closes the transport when the ASGI scope returns.
        logger.warning("授权 WebSocket 关闭帧发送失败，结束连接处理（%s）", type(exc).__name__)
