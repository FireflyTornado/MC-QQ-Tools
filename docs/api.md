# 授权 API

示例面向本机开发环境，使用演示凭据。API Token 用于快照和 WebSocket，Notify Token 专用于内部 POST。远端客户端应通过受控的 HTTPS/WSS 代理接入。

## GET /internal/health

进程存活检查，无需鉴权。建议保留在本机或受信探活网络，Minecraft 插件无需依赖它判断授权是否就绪。

```sh
curl --fail http://127.0.0.1:18765/internal/health
```

成功返回 HTTP 200 和 `{"status":"ok","minecraft_connections":0}`。`status` 是固定的存活标记，`minecraft_connections` 是当前 API 进程登记的 Minecraft 授权 WebSocket 连接数。连接数仅反映授权通道，玩家端口可达性由独立探测判断。数据库状态不参与此响应。

## GET /internal/mc/snapshot

使用 `Authorization: Bearer <API Token>`。Minecraft 插件通过 HTTPS 反代访问该端点，获取完整授权名单。

```sh
curl --fail http://127.0.0.1:18765/internal/mc/snapshot \
  -H 'Authorization: Bearer demo-api-token'
```

```json
{
  "version": 1794141600123,
  "active_names": ["alex", "steve"]
}
```

`version` 是与名单在同一事务中读取的整数授权版本。`active_names` 是目标群的有效游戏名，统一小写并排序。成功响应设置 `Cache-Control: no-store`。

| 错误 | 含义 |
| --- | --- |
| HTTP 401 | 凭据缺失、无效或类型用错。响应为 `{"detail":"Unauthorized"}`，并带有 `WWW-Authenticate: Bearer`。 |
| HTTP 503 | SQLite 不可读或查询失败，`detail` 为 `Authorization database unavailable`。 |
| HTTP 500 | 例如数据库缺少 `auth_state` 版本单行。此类情况应检查初始化和迁移。 |

## GET 和 PUT /internal/mc/banlist

这两个方法使用独立的 `MC_BANLIST_TOKEN`，由唯一的游戏服上报实例通过 HTTPS 调用。未配置该凭据时接口返回 HTTP 404。`GET` 返回当前会话 `{"generation":0}`，首次上报选择返回值加一作为新会话，随机生成 32 位小写十六进制的 `client_id`，从 `sequence: 1` 开始。

```json
{
  "generation": 1,
  "client_id": "34f4ba1441d84d6697da8356a2d837aa5",
  "sequence": 1,
  "entries": {"steve": null, "alex": 1794141600123}
}
```

`entries` 是完整的有效玩家封禁快照，键为游戏名，值为 Unix 毫秒到期时间。永久封禁用 `null`。成功读取的空名单用 `{}`。`PUT` 在 SQLite 提交后返回 HTTP 200 和 `{"generation":1,"sequence":1}`。同一会话的上报按序号递增。重复发送最近已确认的同一快照会收到相同确认。旧会话、跳号或同号但内容不同的快照返回 HTTP 409。上报会话和最近确认位置随数据库持久化，后端重启后可继续重试。

错误凭据返回 HTTP 401。请求超过 2 MiB 返回 HTTP 413，无效快照返回 HTTP 422，SQLite 暂时不可用返回 HTTP 503。插件对网络失败、HTTP 408、429 和 5xx 退避重试。遇到 HTTP 409 会停止上报，检查只部署了一个上报实例后重启插件。

## POST /internal/mc/notify

使用 `Authorization: Bearer <Notify Token>`。只允许本机或受信私网访问，公网反代应拒绝该路径。Bot 在绑定事务提交后调用它，Minecraft 插件通过 WebSocket 接收后续提醒。

```sh
curl --fail -X POST http://127.0.0.1:18765/internal/mc/notify \
  -H 'Authorization: Bearer demo-notify-token'
```

请求无需消息体。API 自行读取数据库中的版本。下例中有一个连接完成发送。

```json
{
  "success": true,
  "version": 1794141600123,
  "delivered": 1
}
```

`success` 表示 API 已处理本次通知。`version` 是读到的授权版本。`delivered` 是当前进程中完成发送的 WebSocket 连接数。同一版本已处理、当前没有连接或连接发送全部失败时，该值可以为 `0`。

无效凭据返回 HTTP 401，响应格式与快照一致。读取授权版本失败或发现版本回退时返回 HTTP 503，`detail` 为 `Authorization update unavailable`。

## WS /internal/mc/ws

WebSocket 握手使用 API Token。Minecraft 插件从受控 WSS 代理接入，用于接收刷新提醒。以下为本机 HTTP Upgrade 请求示例。

```http
GET /internal/mc/ws HTTP/1.1
Host: 127.0.0.1:18765
Upgrade: websocket
Connection: Upgrade
Sec-WebSocket-Version: 13
Sec-WebSocket-Key: dGhlIHNhbXBsZSBub25jZQ==
Authorization: Bearer demo-api-token
```

通过鉴权后，服务端接受连接并发送：

```json
{
  "type": "connected",
  "message": "Minecraft authentication connected"
}
```

版本变更时发送：

```json
{
  "type": "auth_updated",
  "version": 1794141600123
}
```

`type` 标识消息类别，`message` 是连接提示，`version` 是当前授权版本。更新消息只用于触发快照刷新，不包含名单。连接和重连后均应主动拉取完整快照。消息没有持久化、确认或重放机制，不能作为可靠消息队列使用。

广播发送失败时，服务端撤销该连接登记，尝试发送 `1011` 并结束 WebSocket 处理。其余客户端独立接收通知。

凭据无效时，应用在接受连接前拒绝握手并且不会登记连接。应用发送的 ASGI 关闭代码为 `1008`，客户端观察到的握手响应取决于 ASGI 服务与反向代理。代理还需正确转发 Upgrade 和 Authorization 头。
