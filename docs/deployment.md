# MC-QQ-Tools 构建与部署

## Python 3.12 环境

在仓库根目录创建虚拟环境。Ubuntu 24.04 可使用系统的 Python 3.12，若缺少 `venv` 模块则安装系统的 `python3.12-venv` 包。

```sh
python3.12 -m venv .venv
. .venv/bin/activate
python -m pip install -r requirements.txt
python -m pip check
```

Windows 可以用 `py -3.12 -m venv .venv` 创建环境，再通过 `.venv\Scripts\python.exe` 执行同样的 pip、迁移和测试命令。生产运行和本地测试使用同一份依赖锁。Python 服务运行需要 Python、SQLite 和已安装的运行依赖，构建 Minecraft 插件需要独立的 Java 工具链。

## 本地配置与数据库

| 组件 | 模板 | 本机配置位置 | 常用工作目录 |
| --- | --- | --- | --- |
| NoneBot 与 FastAPI | `bot/.env.example` | `bot/.env` | `bot/` |
| NapCat Compose | `napcat/.env.example` | `napcat/.env` | `napcat/` |
| MCQQAuth 授权插件 | `minecraft-plugin/src/main/resources/config.yml` | `plugins/MCQQAuth/config.yml` | Minecraft 服务端根目录 |
| MCQQSudo 模组 | `minecraft-sudo/src/main/resources/config.yml` 和同目录的 `sudo-allowlist.txt` | `config/mcqqsudo/config.yml` 和同目录的 `sudo-allowlist.txt` | Minecraft 服务端根目录 |

在仓库根目录复制 Bot 配置模板，已有 `.env` 时沿用当前文件。

```sh
cp -n bot/.env.example bot/.env
```

`bot/.env.example` 分为公共配置、授权与通知、远程指令三段。按需填写已启用配置的占位值。将 `TARGET_GROUP_ID=0` 改为目标群的数字编号，保留 `ONEBOT_WS_URLS` 的 JSON 数组格式。OneBot Token 要与 NapCat 的实际配置一致。授权插件的 `api.token` 对应 `MC_API_TOKEN`，`MC_NOTIFY_TOKEN` 使用独立的值。进程环境变量优先于 `bot/.env`，显式空值也会覆盖文件值。Bot 和 API 在启动时读取配置，通知客户端在发送时读取。更换凭据时应同步更新调用端并重启相应服务。

启用授权时，在仓库根目录运行 `python bot/migrate.py` 初始化或升级默认数据库 `bot/data/auth.db`。`python bot/migrate.py --db <路径>` 可为迁移命令指定其他目标。升级已有库前先做一致性备份，迁移报错时保留原库并检查原因。

可在仓库根目录用 `python bot/bot.py` 和 `python -m uvicorn --app-dir bot mc_api:app --host 127.0.0.1 --port 18765` 分别启动两个进程。在 `bot/` 中也可继续使用 `python bot.py` 和 `python -m uvicorn mc_api:app --host 127.0.0.1 --port 18765`。

Bot 和 FastAPI 使用同一组组件开关，修改后重启两个进程。未设置 `MC_AUTH_ENABLED` 时，默认启用授权。未设置 `MC_SUDO_ENABLED` 时，沿用 `MC_SUDO_QQ_IDS` 的启用方式。显式设置为 `false` 会关闭对应的命令和 API 路由。两项同时启用时，凭据配置错误会停用出错的组件，另一项仍可启动。

按进程配置授权凭据时，`MC_API_TOKEN` 可只在 FastAPI 服务环境中设置。Bot 启用授权时也会校验自身环境中已配置的 `MC_API_TOKEN`。`MC_NOTIFY_TOKEN` 由 Bot 发送，由 FastAPI 校验，两边须使用相同的值。停用授权时，已配置的这两个 Token 仍须与 Sudo 凭据不同。

只部署 MCQQSudo 时，在现有 `bot/.env` 中设置 `MC_AUTH_ENABLED=false` 和 `MC_SUDO_ENABLED=true`，填好公共配置和 [远程指令凭据](#qq群管理指令)，即可用上述启动命令启动 Bot 和 FastAPI。授权 Token 和 SQLite 仅在启用 MCQQAuth 时需要。只部署 MCQQAuth 时，可设置 `MC_SUDO_ENABLED=false`。

通知默认发送到 `http://127.0.0.1:18765/internal/mc/notify`。可用 `MC_NOTIFY_URL` 覆盖为可信网络上的直连 HTTP(S) 地址。通知请求绕过环境代理并拒绝重定向。

启用授权时，群成员对账在 OneBot 连接成功后自动执行。配置在 `SUPERUSERS` JSON 数组中的超级用户可在目标群使用 `/授权对账` 或 `/reconcile` 触发对账。群详情交叉校验依赖当前 Compose 固定的 NapCat 所提供的 `get_group_detail_info`。名单请求失败、为空、两次名单不一致或人数校验失败时，对账跳过撤权。

`/帮助`（`/help`）、`/认证测试`（`/ping`）和 `/信息`（`/info`）是共用指令。默认帮助始终列出全部指令，并在未启用模块的指令行末标注「未启用」。`/ping` 返回本机 FastAPI 的健康接口状态码与耗时，`/info` 供 `SUPERUSERS` 查看状态图。状态图显示 Bot 账号、采集时间、系统资源和服务链路。授权启用时才读取 SQLite，显示有效绑定数和封禁记录数。采集时间使用 Bot 服务器的当地时间并标注 UTC 偏移。磁盘优先统计 `/opt`，缺失时统计根目录。

封禁记录数包括所有仍有效的游戏服封禁。存在无名 UUID 封禁时，SQLite 节点下方会显示其数量。服务器优先使用具名封禁，使 Bot 能按游戏名同步绑定状态。封禁同步所需的数据库结构在 v1 升至 v2 时一次迁移完成。Minecraft 插件在启动时上报完整封禁快照。

可在 `bot/.env` 设置 `MC_STATUS_ADDRESS=play.example.org:25565`，使用 Minecraft Java 服务器列表探测游戏服，并显示服务器返回的在线人数。地址留空时显示灰色「未探测」，配置读取失败时显示黄色「配置读取失败」。状态图的 Auth WSS 和 Sudo WSS 分别显示授权与指令通道。通道在线时显示绿色，离线时显示红色，API 无法提供连接信息时显示灰色。只显示已启用组件的通道。

昵称来自 OneBot 的当前登录信息。Bot 通过 HTTPS 从 `q1.qlogo.cn` 按自身 QQ 号获取头像，成功缓存 30 分钟，失败缓存 1 分钟。下载不可用时使用内置机器人图标。SQLite 状态、有效绑定数和封禁数来自同一次只读事务。FastAPI 返回 HTTP 200 时亮绿灯，非 200 响应和超时亮黄灯，连接失败亮红灯。

### 机器人文案与群通知

Bot 在启动时读取同目录的 UTF-8 文件 `messages.yml`。在 Ubuntu 首次升级到 3.4.2.2 时，先更新源码和依赖，再创建配置。以下命令适用于以 root 运行的 [Bot unit](../deploy/systemd/mc-qq-bot.service)。创建时使用仅所有者可读写的权限，文件已存在时保留原件。

```bash
cd /opt/mc-qq-auth
sudo sh -c 'umask 077; set -C; cat bot/messages.example.yml > bot/messages.yml'
sudoedit /opt/mc-qq-auth/bot/messages.yml
```

实际运行用户若与模板不同，将文件的读取权限授予该用户，并仅让负责维护的系统用户修改。需要完整示例时查看 [messages.example.yml](../bot/messages.example.yml)，也可以在 `messages.yml` 中只填写要覆盖的字段。例如，以下配置启用自定义欢迎语，关闭主动退群提示，保留被移出群的提示。

```yaml
events:
  join:
    enabled: true
    message: |-
      欢迎 {member} 加入服务器社区！
      请发 /bind 游戏ID 完成绑定。
  leave:
    enabled: false
  kick:
    enabled: true
    retained: "成员 {member_qq} 已被移出群聊，Minecraft 绑定 {mc_name} 仍保留。"
commands:
  bind:
    success: "已绑定 Minecraft ID：{mc_name}"
    banned_name: "游戏 ID {mc_name} 已被封禁，暂时无法绑定。"
  help:
    message: |-
      发送 /bind 游戏ID 绑定账号{auth_enabled}
      发送 /binding 查询{auth_enabled}
info:
  title: "社区授权状态 · 群 {group_id}"
  labels:
    bindings: "有效绑定"
    bans: "封禁记录"
```

三个开关分别控制入群、主动退群和被移出群的消息，默认均为 `false`。现有配置显式设置为 `true` 的通知继续开启。关闭任意开关后，成员事件记录、退群撤权和授权同步照常执行。离群通知按结果选择 `revoked`、`retained`、`no_binding` 或 `failed`。已有冻结或待核实绑定时使用 `retained`。

中文和英文别名共用 `commands` 下的字段。使用 `|-` 编写多行回复，使用 `{{` 和 `}}` 显示字面花括号。通用变量 `{group_id}` 和 `{bot_qq}` 分别是目标群号和机器人 QQ 号，可用于所有文字文案，包括状态图的标题和标签。业务变量的含义和可用字段见模板注释。`{member}` 在欢迎语中生成对新成员的 QQ @。其他动态值和配置文案均作为纯文本发送。可用变量在本次渲染时缺少实际值会显示为 `—`。指令权限继续由目标群和 `SUPERUSERS` 控制。

帮助正文统一使用 `commands.help.message`。授权指令行末可放 `{auth_enabled}`，远程指令行末可放 `{sudo_enabled}`。对应模块启用时占位符为空，未启用时显示 `（未启用）`。已有 `messages.yml` 时，可将旧 `commands.help.shared` 和 `commands.help.sudo` 中要保留的说明移入 `commands.help.message`，再删除旧字段。希望使用新版默认帮助时，删除整个 `commands.help` 自定义配置即可。

已有绑定时，`/绑定` 统一使用 `commands.bind.already_bound`，默认提示如需更换请先使用 `/解绑`。旧配置里的 `commands.bind.frozen` 和 `commands.bind.pending` 可以删除。`/解绑` 和 `/我的绑定` 仍可显示冻结或待核实状态。

已有自定义文案时，将 `commands.bind.banned_name` 中的 `{requested_mc_name}` 改为 `{mc_name}`，并将 `commands.db` 下的 `{qq_id}` 改为 `{member_qq}`。包含旧占位符的文案会回退到该项默认值。

每条文案最多 1000 字符，配置文件最多 64 KiB。缺失字段、空文案、类型错误、非法占位符和过长文案使用该字段的内置默认值。请用布尔值 `true` 或 `false` 设置通知开关。无法解析或读取文件时使用全部内置默认值，群通知因此默认关闭，日志只记录错误类别和已知字段。状态图使用 `info.title` 和仍在显示的 `info.labels.*`，超宽时截断并加省略号。`info.footer` 和隐藏字段的旧标签仍可解析，便于保留已有配置。

状态图由 `bot/status_card.svg` 模板和 Python 依赖 `resvg-py` 生成 PNG。Ubuntu 可用 `sudo apt install fonts-noto-cjk` 为状态图启用中文。缺少中文字体时，Bot 记录一次提示并使用英文备用标签。

日常编辑 `messages.yml` 后，只需重启 Bot。

```bash
sudo systemctl restart mc-qq-bot.service
```

`bot/messages.yml` 是 Git 忽略的生产配置。后续升级沿用该文件，可在升级前将其备份到私有目录。安全升级工具会拒绝与现有本地文件冲突的传入路径。首次升级前已创建配置的环境，应先将该文件移到私有备份目录，升级完成后放回。后续模板新增字段会由内置默认值补齐。

## Ubuntu systemd 部署

[API unit](../deploy/systemd/mc-qq-api.service) 和 [Bot unit](../deploy/systemd/mc-qq-bot.service) 是按部署约定编写的候选模板。上线前核对当前安装的 unit 和 drop-in。两个服务以 `root` 运行，工作目录为 `/opt/mc-qq-auth/bot`，解释器使用 `bot/.venv/bin/python`。前文的仓库根目录虚拟环境用于本地开发，systemd 使用 `bot/.venv`。

首次准备生产虚拟环境时，可在 `/opt/mc-qq-auth` 执行以下命令。升级已有环境时按后文先停服务。

```sh
cd /opt/mc-qq-auth
sudo python3.12 -m venv bot/.venv
sudo bot/.venv/bin/python -m pip install -r requirements.txt
```

准备好 `bot/.env` 后再启动服务。启用授权的部署还需要已初始化的 SQLite 数据库。unit 文件采用 `root:root`、`0644`。由 root 持有的 `bot/.env` 和数据库宜使用 `0600`，`bot/data/` 宜使用 `0700`。应用从自己的目录读取 `bot/.env`。unit 的 `UMask=0077` 限制新建文件的默认权限，已有文件权限需单独核对。

Bot 通过 `Wants=` 请求一同启动 API，通过 `After=` 排在 API 的启动作业之后。API 启动失败时 Bot 仍可保存绑定，通知失败由现有机制处理。`Type=exec` 只确认解释器已执行，API 就绪需检查本机端口和健康接口。NapCat 仍由 Compose 独立管理。

### 安装与验证 unit

先查看实际加载的文件和覆盖项的位置。在本机对照现有内容，保留必要的覆盖配置。

```sh
sudo systemctl show -p FragmentPath -p DropInPaths mc-qq-api.service mc-qq-bot.service
```

在维护窗口由管理员执行下例。先写入与安装目录同一文件系统的临时目录，检查非空和语法，再逐个原子替换。两份文件都安装后才重新加载 systemd 配置。

```sh
sudo bash <<'SH'
set -euo pipefail

source_dir=/opt/mc-qq-auth/deploy/systemd
unit_dir=/etc/systemd/system
units=(mc-qq-api.service mc-qq-bot.service)
test -x /opt/mc-qq-auth/bot/.venv/bin/python

stage=$(mktemp -d "$unit_dir/.mc-qq-auth.XXXXXX")
trap 'rm -f -- "$stage/mc-qq-api.service" "$stage/mc-qq-bot.service"; rmdir -- "$stage"' EXIT

for unit in "${units[@]}"; do
    destination="$unit_dir/$unit"
    if [[ -L "$destination" || ( -e "$destination" && ! -f "$destination" ) ]]; then
        printf 'Review existing unit path before installing: %s\n' "$destination" >&2
        exit 1
    fi
    test -s "$source_dir/$unit"
    install -o root -g root -m 0644 -- "$source_dir/$unit" "$stage/$unit"
    test -s "$stage/$unit"
done

systemd-analyze verify --recursive-errors=no "$stage/mc-qq-api.service" "$stage/mc-qq-bot.service"

backup=$(mktemp -d /var/backups/mc-qq-auth-units.XXXXXX)
for unit in "${units[@]}"; do
    if [[ -s "$unit_dir/$unit" ]]; then
        install -o root -g root -m 0600 -- "$unit_dir/$unit" "$backup/$unit"
    fi
done
printf 'Previous unit backup directory: %s\n' "$backup"

for unit in "${units[@]}"; do
    mv -fT -- "$stage/$unit" "$unit_dir/$unit"
done
systemctl daemon-reload
SH
```

`daemon-reload` 更新 unit 定义，运行中的进程要到下次启动或重启才使用新定义。若遇到 0 字节 unit 或写入中断，先用 `sudo test -s /etc/systemd/system/mc-qq-api.service` 和 Bot 对应路径检查，再查看 `systemctl show` 的 `LoadState`、`FragmentPath` 和 `DropInPaths`。在文件齐全前暂停 reload 和 restart。用可信的非空备份或仓库模板重新做临时验证与替换。若两份文件只替换了一份，先恢复或完成另一份，再执行 `daemon-reload`。符号链接和手动屏蔽状态由管理员先核对。

### 服务管理与健康检查

按需要选择启动、停止或重启。首次上线先启动 API，检查健康后再启动 Bot。

```sh
sudo systemctl start mc-qq-api.service
sudo systemctl start mc-qq-bot.service

sudo systemctl stop mc-qq-bot.service
sudo systemctl stop mc-qq-api.service

sudo systemctl restart mc-qq-api.service mc-qq-bot.service
```

两个服务由 systemd 在异常退出 5 秒后尝试重启。管理员停止服务时，systemd 先发送 SIGTERM，若 30 秒内仍未退出则强制终止。开机自启由管理员显式控制，`enable` 和 `disable` 本身不启动或停止进程。若只取消 API 自启，已启用的 Bot 仍可在启动时拉起 API。

升级到阶段 3.4.2 时，按维护流程重启 `mc-qq-api.service` 和 `mc-qq-bot.service`，先确认 API 健康再启动 Bot。现有 SQLite 结构和依赖锁可直接使用。游戏端继续使用现有插件，API 重启后插件会自动重连并读取最新快照。

升级到阶段 3.4.2.1 时，用更新后的 `requirements.txt` 安装 Pillow 和 psutil，再重启 Bot 以加载新指令。手工更新时，API 本身无需重启。现有升级工具会将依赖锁变更视为共享依赖更新，并安排重启 API 和 Bot。

升级到阶段 3.4.2.2 时，用更新后的 `requirements.txt` 安装 PyYAML。现有安全升级工具会提前下载锁定的 wheel，并按共享依赖更新流程重启 API 和 Bot。升级完成后可按 [机器人文案与群通知](#机器人文案与群通知) 创建或恢复配置。

升级到阶段 3.4.2.3 时，沿用现有 `messages.yml`。手工升级源码后重启 Bot 即可使用新版卡片，建议先确认服务器时区和中文字体。

```sh
sudo systemctl enable mc-qq-api.service mc-qq-bot.service
sudo systemctl disable mc-qq-api.service mc-qq-bot.service
```

```sh
sudo systemctl status mc-qq-api.service mc-qq-bot.service --no-pager
sudo journalctl -u mc-qq-api.service -u mc-qq-bot.service -n 100 --no-pager
sudo journalctl -u mc-qq-api.service -u mc-qq-bot.service -f
```

确认 `18765` 只监听 `127.0.0.1`，健康接口返回 `"status":"ok"`。授权启用时，未带凭据的快照请求返回 `401`。启用 Sudo 时，`sudo_connected` 为布尔值，`false` 表示路由已挂载且游戏服尚未连接。关闭 Sudo 时该字段为 `null`。只部署 MCQQSudo 时，健康响应应包含 `"auth_enabled":false`。Bot 启动日志中的 `MC-QQ-Tools components` 记录实际加载的组件。数据库完整性另行核验。

```sh
sudo ss -ltnp 'sport = :18765'
curl -fsS http://127.0.0.1:18765/internal/health
# 授权启用时执行下一条快照检查。
test "$(curl -sS -o /dev/null -w '%{http_code}' \
  http://127.0.0.1:18765/internal/mc/snapshot)" = 401
```

### 安全升级工具

`deploy/update.sh` 用于 Ubuntu 24.04 上的人工升级。它兼容原仓库 `FireflyTornado/MC-qq-auth-BF656` 和新仓库 `FireflyTornado/MC-QQ-Tools` 的 HTTPS 与 SSH 地址。现有部署继续使用 `/opt/mc-qq-auth`，其中的运行配置、数据库、虚拟环境和 NapCat 数据可直接沿用。

首次升级到支持新名称的版本使用下方手工流程，因为这一版同时更新了升级工具和 systemd 模板。安装新版工具后可更新远程地址，再用 `--check` 核对。

```sh
cd /opt/mc-qq-auth
git remote set-url origin https://github.com/FireflyTornado/MC-QQ-Tools.git
sudo bash deploy/update.sh --check
```

从生产工作目录执行，检查和升级分别选择一条命令。

```sh
cd /opt/mc-qq-auth
sudo bash deploy/update.sh --check
sudo bash deploy/update.sh --check --local
sudo bash deploy/update.sh --apply REVIEWED_COMMIT_SHA
```

- 默认模式与 `--check` 相同。它获取 GitHub 的 `main`，更新 Git 对象和 `FETCH_HEAD`，验证远端历史未回退或分叉后才更新 `origin/main`。它检查工作区、目标变更、配置文件和服务状态。
- `--check --local` 只使用缓存的 `origin/main`，不联网或更新 Git 引用，因此无法确认 GitHub 是否有新提交。两种检查模式都保持源码、虚拟环境、数据库和服务原样。
- `--apply` 接受完整的 40 位 SHA 或至少 7 位且能唯一解析的十六进制前缀。执行前将 `REVIEWED_COMMIT_SHA` 替换为已审核的 SHA。目标必须在本次取得的 `origin/main` 历史中，并能从当前 `HEAD` 快进。管理员须先核对目标完整 SHA 与 CI 结果，再决定是否进入维护窗口。工具不会查询或替代这项审核。

使用前确认已有 `main` 跟踪 `origin/main`，root 能访问预期 GitHub 仓库，现有 unit、回环端口、NapCat 容器名和运行目录符合本页约定。工具需要 Git、Bash、`flock`、systemd 工具、Docker CLI、curl、iproute2 和已有的 `bot/.venv`。启用授权的部署还需要 `sqlite3`。工具按 `mc-qq-api.service` 和 `mc-qq-bot.service` 各自已加载的环境判定 Auth 和 Sudo 的启用状态，进程环境优先于 `bot/.env`。任一单元的组件状态无法判定，或两边对同一个组件的启用状态不一致时，工具会中止。如果修改了 unit 或 drop-in，先运行 `systemctl daemon-reload`。运行工具期间避免其他管理员并发修改 Git、运行目录或服务。`flock` 锁住仓库的 `.git` 目录，仅约束使用同一锁的升级工具进程。

Apply 按以下顺序执行。

1. 验证 Git 工作区和目标。存在未解决的 Git 操作、源码修改、非忽略的未跟踪文件或与目标冲突的忽略路径时停止。当前索引和目标都必须排除 `bot/.env`、`napcat/.env`、`bot/data/`、`napcat/data/`、`bot/.venv/`。
2. 按差异制定操作。Bot 或 API 专属代码只重启受影响服务，依赖或共享 Python 代码变更涉及两项服务，纯文档和构建变更只更新源码。systemd unit、NapCat 运行配置或升级工具有改动时，转到下方的手工流程。
3. 涉及服务时先核验现有 API、Bot 和 NapCat 的健康。依赖有变化时，先按目标锁文件从 PyPI 下载 wheel。下载失败时服务保持运行。
4. 涉及服务且启用授权时，先做在线 SQLite 一致性备份，再停受影响服务。需要迁移时先停 Bot 再停 API，确认两者均为 `inactive`，并在停写后再做一份备份。停写期间管理员也要暂停其他数据库写入者。
5. 用 `git merge --ff-only --no-autostash --no-overwrite-ignore` 合入已经审核的 SHA。涉及依赖时，停服期间从预下载的 wheel 离线安装并运行 `pip check`。启用授权时，对比活动库的 `user_version` 与目标代码的数据库版本。需要迁移时运行 `bot/migrate.py`，确认 `quick_check` 通过且数据库版本与目标一致。只部署 MCQQSudo 时可直接升级源码和依赖。
6. 启动受影响服务，验证 API 的进程 PID、`127.0.0.1:18765` 监听和健康 `200`。按预期启用状态核对健康响应的 `auth_enabled` 和 `sudo_connected`，启用授权时再核对未授权快照返回 `401`。最后在本次 Bot 启动的 journald 日志中核对实际加载的组件与 OneBot 连接记录，并确认进程已连接 `127.0.0.1:13001`。运行中的 Bot 缺少预期组件记录时，工具会安排重启并验证。NapCat 保持运行，日志级别需保留 OneBot 的连接记录。

备份保存在仓库外的 `/opt/mc-qq-auth-backups/`。目录要求 root 所有、权限 `0700`，每份备份使用独立文件名，权限 `0600`。工具使用 SQLite `.backup`，只有非空、`quick_check` 返回 `ok` 且 `user_version` 与源一致的文件才会被报告为有效备份。中断留下的文件需重新核验后才能用于恢复。

重新启用 MCQQAuth 时，先确认授权数据库版本。如果当前 API 仍以 MCQQSudo 模式健康运行，可以用 `--apply` 当前 SHA 补做待执行的迁移。服务已停止时，按下方手工流程停写、备份并执行 `bot/migrate.py`。数据库版本不符时，Bot 和 API 会暂停授权功能。已正确配置的 MCQQSudo 可以继续启动。

#### 失败后的人工恢复

| 报告阶段 | 已完成的操作与下一步 |
| --- | --- |
| `preflight`、`wheel-download`、`backup` | 源码和服务保持原状。核对 Git 或运行配置，修复下载或备份错误后再试。 |
| `stop`、`offline-backup` | 可能已有服务停止，源码仍为原版本。核对 unit 状态与库状态，确认安全后可手工启动原服务。 |
| `git-update` | 相关服务已停止。检查 `git rev-parse HEAD` 和 `git status --short`，确认当前版本及本地文件，再决定后续。 |
| `dependencies` | 源码已更新，虚拟环境可能只安装了一部分。用经审核的锁文件完成安装并通过 `pip check`，或准备与旧版本配套的环境。 |
| `migration` | 源码已更新，数据库可能需要确认迁移结果。保持停写，核对活动库和最近一份有效备份的结构版本与完整性。 |
| `restart`、`health` | 工具会尝试停止本次尝试启动的服务。核对实际状态、有效 unit、端口和本机日志，再决定何时启动。 |

工具不会回退 Git 或恢复数据库。回到旧源码前应确认它能读取当前数据库结构。迁移可能没有逆向步骤，恢复旧备份可能丢失随后产生的绑定。需要恢复时先停写，保全当前库及最后一份有效备份，再由管理员选择恢复点。对当前 SHA 执行 Apply 只处理可识别的待执行授权迁移。其他失败按上述阶段恢复。

### NapCat 容器状态说明

升级工具可能输出 `NapCat running none.`。

其中 `running` 表示 Docker 容器处于运行状态，而 `none` 表示该容器没有配置 Docker Healthcheck，并不代表 NapCat 未启动。

对于当前部署，`running none` 属于可接受的容器状态。升级工具还会在涉及服务更新时检查 API 和 OneBot 的实际连接状态。

如需独立确认容器是否正常运行，可以执行：

```bash
docker inspect mc-qq-napcat --format '{{.State.Status}}'
```

正常情况下应输出 `running`。

### 手工升级流程

1. 在 `/opt/mc-qq-auth` 执行 `git fetch origin`。在同一个 Bash 会话中用 `previous_commit=$(git rev-parse HEAD)` 和 `target_commit=$(git rev-parse --verify 'origin/main^{commit}')` 记录两个完整 SHA。核对目标提交的变更和 GitHub CI 结果。
2. 确认当前分支为 `main`，`git status --short` 没有待处理变更，并用 `git merge-base --is-ancestor HEAD "$target_commit"` 确认可快进。`target_commit` 应设为已核对的完整 SHA。检查目标提交不会跟踪或覆盖现有私有配置和数据。下例应没有输出，其他自定义运行路径也应核对。

   ```sh
   git ls-tree -r --name-only "$target_commit" -- .env .venv bot/.env bot/.venv bot/data bot/messages.yml napcat/.env napcat/data
   ```

3. 启用授权时用 SQLite `.backup` 做一致性快照。以下命令在同一个 Bash 会话中执行，要求已安装 `sqlite3` CLI。遇到任一步失败都应停在备份阶段。只部署 MCQQSudo 可直接执行下一步。

   ```sh
   db=/opt/mc-qq-auth/bot/data/auth.db
   sudo test -s "$db" &&
     backup_dir=$(sudo mktemp -d /var/backups/mc-qq-auth-db.XXXXXX) &&
     backup_db="$backup_dir/auth.db" &&
     sudo sqlite3 -readonly -batch -bail "$db" ".backup '$backup_db'" &&
     sudo test -s "$backup_db" &&
     test "$(sudo sqlite3 -readonly -batch -bail "$backup_db" 'PRAGMA integrity_check;')" = ok
   ```

4. 停止受影响服务。更新两项服务共用的源码、依赖或数据库时，先停 Bot，再停 API。需要迁移时，同时停止其他写入者，并在停写后重新执行第 3 步，在新的私有备份目录中保存最后一次已提交的状态。保留已有 `.env`、`bot/messages.yml`、`bot/data/`、`bot/.venv/` 和 NapCat 挂载。
5. 快进到已核对的提交，并拒绝覆盖已忽略的本地文件。快进失败或当前 SHA 与审核目标不同，先查明原因。安装锁定依赖时使用生产虚拟环境，保持相关服务停止。

   ```sh
   git merge --ff-only --no-autostash --no-overwrite-ignore "$target_commit" &&
     test "$(git rev-parse HEAD)" = "$target_commit" &&
     sudo /opt/mc-qq-auth/bot/.venv/bin/python -m pip install -r requirements.txt
   ```

6. 授权已启用且需要迁移时，先确认停写后的备份非空且完整性检查通过，再运行 `sudo /opt/mc-qq-auth/bot/.venv/bin/python bot/migrate.py`。迁移成功后确认活动数据库非空，并用 `sudo sqlite3 -readonly -batch -bail "$db" 'PRAGMA integrity_check;'` 验证返回 `ok`。
7. 用生产虚拟环境执行 `python -m pip check` 和 `python -m unittest discover -s tests -v`。测试使用临时 SQLite 和合成配置。若 unit 有变更，按安装步骤验证并重新加载。
8. 先启动 API，核对回环端口、健康与未授权响应，再启动 Bot 并检查两项服务的日志。若安装、迁移、测试或健康检查失败，停止已启动的受影响服务，核对上一版本的源码和依赖以及最后一份有效备份，再决定恢复步骤。

## NapCat Compose

在 `napcat/` 下将 `.env.example` 复制为 `.env`，填写自己的 WebUI Token。已有部署应保留实际 `.env`，并核对 `data/qq`、`data/config`、`data/plugins` 的原挂载位置，以便继续使用现有会话和配置。

```sh
cd napcat
cp -n .env.example .env
# Fill WEBUI_TOKEN before starting NapCat.
docker compose up -d napcat
```

Compose 将 WebUI 映射到本机 `6099`，将容器内的 OneBot WebSocket `3001` 映射到本机 `13001`。两个宿主机端口均绑定回环地址。在 NapCat 中配置 OneBot 正向 WebSocket 服务及其 Token，Bot 的默认模板连接 `ws://127.0.0.1:13001/`。

## HTTPS/WSS 反向代理边界

FastAPI 按上面的命令监听回环地址。授权快照 `/internal/mc/snapshot` 和封禁名单 `/internal/mc/banlist` 共用现有 HTTPS 域名、证书和 FastAPI 上游。Nginx 可在现有 HTTPS `server` 块内引入 [共用 HTTP 规则](../deploy/nginx/mc-qq-api-http.conf)，替换原来的 `/internal/mc/snapshot` 单路径规则。如果已经为 `/internal/mc/banlist` 建了单独规则，一并替换。原规则上的来源限制应应用到共用规则。封禁上报需要允许 `GET` 和 `PUT`。

```nginx
include /opt/mc-qq-auth/deploy/nginx/mc-qq-api-http.conf;
```

共用规则仅匹配上述两个完整路径，保留请求路径和 `Authorization` 头，关闭代理缓存，并允许最多 2 MiB 的请求体。代理应转发 FastAPI 的 HTTP 409 和 `X-MCQQ-Banlist-Conflict` 响应头，供插件区分首次会话竞争。避免在代理日志中记录 `Authorization`。`/internal/mc/ws` 继续使用该域名下的 WebSocket 规则。WebSocket 转发需支持 HTTP/1.1 Upgrade 并设置合适的空闲超时。

`/internal/mc/notify` 应从公网路由中排除，`/internal/health` 留给本机或受信探活。WebUI 和 OneBot 端口同样保留在回环地址。完整协议见 [API 文档](api.md)。

### 玩家封禁名单联动

在 `bot/.env` 设置独立的 `MC_BANLIST_TOKEN`。停用 Sudo 时，保留在配置中的 Sudo Token 仍须与封禁名单 Token 不同。从旧版本升级时，运行 `python bot/migrate.py` 将数据库从 v1 迁移到 v2，然后重启 FastAPI 和 Bot，并使用上面的共用 HTTPS 规则。随后更新 Minecraft 插件。每个授权数据库只启用一个上报实例。

```yaml
banlist-watch:
  enabled: true
  upload-url: "https://auth.example.org/internal/mc/banlist"
  token: "REPLACE_WITH_MC_BANLIST_TOKEN"
```

填入与后端相同的 Token，换上插件 JAR 并重启 Minecraft。首次上报成功时，控制台会记录「封禁名单快照已获 API 确认」。测试服可用一个已绑定账号执行 `/ban`，检查 `/我的绑定` 和 `/解绑`，再执行 `/pardon`，等待 Bot 核实群成员状态。上报地址和 Token 均留空时，插件只记录本地监听日志。

### QQ群管理指令

请勿通过 `/sudo` 发送含密码、令牌或其他凭据的指令。指令参数会出现在群消息中，FastAPI 也会记录完整指令。需要输入凭据时，请直接使用 Minecraft 服务端控制台。

默认关闭。启用时，在 Bot 和 FastAPI 共用的 `bot/.env` 的远程指令段设置以下变量。群号取公共配置中的 `TARGET_GROUP_ID`，账号名单与 `SUPERUSERS` 独立。

```dotenv
MC_SUDO_QQ_IDS=["123456789"]
MC_SUDO_BOT_TOKEN=REPLACE_WITH_MC_SUDO_BOT_TOKEN
MC_SUDO_MOD_TOKEN=REPLACE_WITH_MC_SUDO_MOD_TOKEN
```

把两个 Token 分别替换为独立的随机值，并与授权、通知、封禁名单上报和 OneBot 的凭据区分。Auth 停用时，Sudo 仍会检查已配置的封禁名单 Token。Bot 用 `MC_SUDO_BOT_TOKEN` 向 FastAPI 提交指令、读取后续回执和结束等待，固定使用回环地址 `127.0.0.1:18765`。模组的 `sudo.token` 对应 `MC_SUDO_MOD_TOKEN`，按进程配置时可只在 FastAPI 服务环境中设置后者。旧部署可沿用 `MC_SUDO_PLUGIN_TOKEN` 的同一值，也可以只改键名。Bot 启用 Sudo 时也会校验自身环境中已配置的 Mod Token。两个键同时设置时值必须一致。反代只为游戏主机增加 `/internal/mc/sudo/ws` 的 WSS 路由，转发 Upgrade、Authorization 和 `X-MCQQSudo-Capabilities` 请求头，尽可能限制到游戏主机的来源地址。将 `/internal/mc/sudo/submit`、`/internal/mc/sudo/followup/` 和 `/internal/mc/sudo/stop` 留在回环地址。FastAPI 继续使用单 worker，两个主机应同步时钟，时差控制在一秒内。

用 JDK 17 构建 MCQQSudo，将产物放入 Mohist 1.20.1 服务端的 `mods/`。

```bash
mvn -B -ntp -f minecraft-sudo/pom.xml clean verify
```

产物是 `minecraft-sudo/target/MCQQSudo-0.3.2.jar`，包含指令通道、输出收集器和 Forge 异步输出转换器。Forge 加载 ID 为 `mcqqsudo`。升级时先停服，移出原 MCQQSudo JAR 和已安装的 `MCQQSudo-Transformer-*.jar`，然后将新 JAR 放入 `mods/`。原有 `config/mcqqsudo/` 可直接沿用。MCQQAuth 授权插件可按需安装。新安装时会创建 `config/mcqqsudo/config.yml` 和同目录的 `sudo-allowlist.txt`。配置默认关闭。将 `MC_SUDO_MOD_TOKEN` 的值填入 `sudo.token` 并设置 WSS 地址，修改后重启游戏服生效。

```yaml
sudo:
  enabled: true
  websocket-url: "wss://auth.example.org/internal/mc/sudo/ws"
  token: "REPLACE_WITH_MC_SUDO_MOD_TOKEN"
```

从早期独立模组升级时，停服后替换旧模组 JAR。若已存在 `config/mcqqauth-sudo/` 且没有 `config/mcqqsudo/`，模组继续读取旧目录。要迁移路径，可在停服后把配置和允许清单一起复制到 `config/mcqqsudo/`。两个目录同时存在时，模组以新目录为准。

从插件版迁移时，先停游戏服。将旧插件配置的 `sudo` 段和值、仍需使用的 `sync.reconnect-min-ms` 和 `sync.reconnect-max-ms`、以及 `sudo-allowlist.txt` 复制到模组配置目录。如果已安装旧收集器 `mcqqauth-output-capture-0.1.0.jar`，用新模组替换它。将授权插件升级到 0.7.0，或在继续使用旧插件时设其 `sudo.enabled: false`。FastAPI 同时接收一个游戏端 `/sudo` 连接。使用已有扩展回显协议的 Bot 和 FastAPI 可直接接入新模组。

允许清单默认留空。用 UTF-8 编辑，每行一个命令前缀，省略开头的 `/`。空行和以 `#` 开头的行会被跳过。若曾配置 `sudo.allowed-commands`，把仍需开放的条目移入新清单。

保存清单后，在 Minecraft 服务端控制台执行 `mcqqsudo reload`。该指令只重载允许清单。Mod 校验整份清单后立即替换规则，并报告加载条数。文件无法读取或格式无效时，Mod 清空生效规则并暂停群内 `/sudo`。修正文件后再次执行同一条指令即可恢复。

前缀按大小写和空格边界匹配。写入 `whitelist` 即开放服务端当前注册为 `whitelist` 的命令及其所有子命令。只写 `whitelist add` 则允许 `/sudo whitelist add Steve`，并拒绝 `whitelist remove Steve` 和 `whitelist addall Steve`。匹配后，Mod 把命令原样交给服务端，短名按 Mohist 当前的命令注册表解析，参数由服务端校验。`commands.yml` 配置的服务端命令别名会被拒绝。

如果从自动补 `minecraft:` 的旧版本升级，请检查清单里的短名规则。需要固定执行原版白名单命令时，将旧规则 `whitelist add` 改为 `minecraft:whitelist add`，群里对应发送 `/sudo minecraft:whitelist add Steve`。插件和模组命令同样可以使用服务端实际注册的短名或完整名称，群消息中的写法须与清单匹配。

启用后可在清单中加入 `list` 和 `say`，在控制台执行 `mcqqsudo reload`，再用指定 QQ 在目标群发 `/sudo list` 和 `/sudo say 回显测试`，检查执行者反馈和广播。也可以用 `/sudo list invalid-argument` 检查语法错误的回显。Minecraft 原版白名单独立于本项目的 QQ 绑定准入。

Mod 为每条命令创建独立的远程执行者和输出会话，并采集本次命令产生的玩家聊天、标题、动作栏、部分音效和粒子通知、命令线程的 Log4j 日志，以及原版聊天和数据包重载的回调。首轮无输出且仍在等后续结果时，群内显示「正在执行...」。如果命令首轮执行失败并已登记后台任务，群内先提示失败，随后补发后台结果。RCON 监听端口可保持关闭。需要实际服务端控制台身份的指令须在控制台运行。

Mod 的 `sync.reconnect-min-ms` 和 `sync.reconnect-max-ms` 分别默认为 1000 和 30000 毫秒。`/sudo` 通道在握手成功或收到匹配的 Pong 后等待 20 秒发送 Ping，Pong 超时为 15 秒。心跳超时后按配置退避重连。

Mod 在当前请求的等待窗口内收集已关联的原版回调。回调超时会返回已收集内容并提示采集不完整。Mod 同时镜像原版和 JVM 标准 `System.out`、`System.err` 的直接写入，并保留 Mohist 已转发至 Log4j 的行。持有旧输出流引用、直接写文件描述符、子进程及第三方自行替换的输出流需要各自的接入方式。

原版 `/schedule function` 实际入队后，群里先收到带请求编号的首轮回执，可以继续提交其他指令。函数执行产生的输出会按同一请求编号分批补发，超过文本阈值时发为 TXT。可以用包含 `say 预约回显测试` 的数据包函数试验 `/sudo schedule function demo:notice 45s`，再用 `schedule clear demo:notice` 或重新预约验证取消和替换。一个请求安排多个函数时，相关函数全部执行或取消后结束回传。

通过 Mohist 的 Bukkit 调度器提交的一次性同步、异步或延迟任务也会关联到正在执行的 `/sudo` 请求，包括 `callSyncMethod` 提交的任务。任务执行或取消后复用后续回执。可用测试插件安排一项异步任务和一项延迟任务，验证普通请求在等待期间仍能执行，并用任务取消验证回执。周期任务继续按 Bukkit 的原有规则运行。

#### Forge 异步输出采集

Forge 在启动时从 MCQQSudo JAR 注册转换器，再直接挂载其中的主模组。部署时只需放入一个 MCQQSudo JAR，内嵌模组无需手动解包。转换器扫描 `mods/` 中独立安装的模组，接入 JDK 定时线程池的 `execute`、`submit` 和单次 `schedule`，以及普通 JDK 线程池的 `submit`。日志中的 `MCQQSudo executor capture targets` 显示找到的类数。只有在 `/sudo` 或其后台任务中提交的新任务才会继承请求归属。可用 Forge spark 的 `/sudo spark gc` 验证结果是否补发。

转换器还会接入 JDK `CompletableFuture` 的 `runAsync`、`supplyAsync`，以及 `CompletableFuture` 和 `CompletionStage` 上的 `thenRun`、`thenApply`、`thenAccept`、`thenCompose`、`whenComplete`、`handle`、`exceptionally`、`exceptionallyCompose` 及其异步重载。回调归属在注册时确定，等待对应阶段结算后再结束回执。通过普通 Java 方法引用调用这些入口时，以实际调用时的请求为准。可序列化的方法引用沿用原有调用方式。

普通 `ThreadPoolExecutor.execute` 保留原 Runnable 的队列身份，继续由原线程池处理。调度器包装器（包括 `Executors.newSingleThreadScheduledExecutor()`）和自定义调度器保留原有的提交行为。这些任务与自定义 Future、内嵌依赖、Bukkit 插件 JAR、周期任务和组合多个 Future 的 API 一样，可使用原有采集路径或下述显式接口。

线程池的队列维护、关闭操作和拒绝策略沿用 JDK 原生行为。有明确的任务取消、执行结束或提交异常时，采集器据此结清回执。`shutdownNow()` 返回的工作项如果随后在备用执行器上运行，输出仍归原请求。第三方静默丢弃或直接移除任务时，如果一直没有可确认的终态，回执会等到最长一小时的后台采集窗口到期。

插件用自有线程、线程池或回调时，将 `MCQQSudo-0.3.2-game.jar` 作为编译期依赖。运行 `mvn -B -ntp -f minecraft-sudo/pom.xml clean install` 后，可声明 Maven 依赖 `cn.nightbugclub:MCQQSudo:0.3.2`，设置 `classifier` 为 `game`、`scope` 为 `provided`。已下载发布包时，也可从外层 JAR 的 `META-INF/mcqqsudo/` 提取该文件并加入编译类路径。游戏服的 `mods/` 只安装外层发布 JAR。

在任务提交点使用 `cn.nightbugclub.mcqqauthcapture.CommandOutputContext.wrap(Runnable)` 或 `wrap(Consumer<T>)`。每次包装绑定一次调用，普通玩家或控制台触发的任务仍按原逻辑执行。若提交失败或任务在开始前取消，请调用包装对象的 `close()` 解除回传等待。此方法只解除关联，实际取消仍由插件的调度器处理。

```java
var task = CommandOutputContext.wrap(() -> runBackgroundWork());
try {
    executor.execute(task);
} catch (RejectedExecutionException failure) {
    task.close();
    throw failure;
}
```

需要返回值的线程池任务可以用 `CommandOutputContext.supplyAsync` 提交。用 `whenComplete` 或 `whenCompleteAsync` 注册完成回调可以在 Future 的完成线程或指定线程池恢复原请求。回调里登记的下一项任务继续使用同一个请求，前一项任务结束后仍能补发后续结果。

```java
var report = CommandOutputContext.supplyAsync(this::buildReport, worker);
CommandOutputContext.whenComplete(report, (data, failure) -> {
    if (failure != null) {
        sender.sendMessage("生成报告失败");
        return;
    }
    CommandOutputContext.whenComplete(uploadAsync(data), (link, uploadFailure) ->
            sender.sendMessage(uploadFailure == null ? link : "上传报告失败"));
});
```

这些接口会在任务完成、失败或取消时结束对应的等待。已有的 `CompletionStage` 也可以在当前请求中用 `track(stage)` 登记，等它结束后再关闭回传。`track` 只延长等待，阶段里的日志和广播需要通过带上下文的任务或回调采集。

已登记的延后任务最多跟踪一小时。任务静默完成或回传超时时，群内仍会收到终态。对于没有登记延后任务的请求，Mod 在发出首轮回执后继续接收原命令发送者的直接回复 30 秒。执行阶段抛出异常时，首轮仍报告失败，并保留这段接收时间。到期且始终未收到输出时，群内显示「等待期间未捕获输出」。首轮采集不完整时，群内会提示采集状态并继续等待迟到输出。Bot 在 OneBot 确认发送后推进补发序号，发送失败时在投递期限内重试。API 在 Bot 投递期间保留回执，收到终帧确认后清理正文。首轮的执行失败或采集不完整提示发送异常时，下一条成功投递的补发会附带该提示。回传状态保存在内存中，指令连接断开会结束本次回传，游戏服继续执行已安排的任务。请更新 Mod、FastAPI 和 Bot 以启用分批补发。

`/sudostop` 由目标群内配置在 `MC_SUDO_QQ_IDS` 中的账号使用。指令会结束受理时已进入后续回传、且还没有终态的全部请求，包含 30 秒直接回复窗口和最长一小时的后台等待。群内会显示「已停止 x 条指令的回传等待」或「当前没有等待回传的指令」。游戏服已开始的命令和后台任务可以继续运行。旧模组仍可使用普通 `/sudo`，升级模组后 `/sudostop` 也会释放游戏服侧的采集名额。

游戏服未确认清理时，再次发送 `/sudostop` 可重试同一连接上的待清理请求。群回执按本次新停止的请求数计算。

可在 `bot/.env` 的远程指令段调整输出发送方式。阈值只按输出正文判断，请求编号和状态提示不计入。超过任一阈值就转为 TXT，恰好等于阈值时仍可直接发文本。

| 配置 | 默认 | 判断方式 |
| --- | --- | --- |
| `MC_SUDO_FILE_LINE_THRESHOLD` | `10` | 输出行数，末尾单个换行不会新增一行。最小值为 1。 |
| `MC_SUDO_FILE_LINE_CHAR_THRESHOLD` | `0` | 任意一行的字符数，0 表示关闭该判断。 |
| `MC_SUDO_TEXT_MAX_CHARS` | `1500` | 单条输出正文的总字符数，包含换行。最小值为 1。 |

TXT 通过 NapCat 的 `upload_group_file` 发送到群内。首轮文件上传最多等待 20 秒，期间 Bot 占用指令处理名额。补发文件在 20 秒内最多尝试三次，失败或未获确认时改发带请求编号的文本，并保留完成或超时状态。Mod 在接收命令反馈时限制累积大小。超过 64 KiB 时，保留由完整字符组成的原文前缀，并在文件末尾标注截断，标注也计入 64 KiB。编码后的回执另有 512 KiB 上限。首次部署此功能需要更新 Bot、FastAPI 和游戏服 Mod。Bot 和 FastAPI 共用 Sudo 启用判定，修改这三个阈值后重启两项服务。

每次提交一条纯文本命令。在机器人发出结果前，新的请求会收到忙碌提示。Bot 在同一进程内保留消息 ID 5 分钟，忙碌时拒绝的指令也会登记，以忽略 OneBot 重投。去重缓存满时，Bot 暂停接收新指令，最后一次因容量拒绝的消息再过 5 分钟才恢复接收。等待满 30 秒会回复「执行超时，结果未知」。若 API 先检测到游戏端指令连接断开且仍在等回执，Bot 会立即回复「指令通道已断开，执行结果未知」，回复后释放名额。两种情况下，已经开始的指令仍可能完成，机器人不会自动重发。Bot 和 FastAPI 日志可按请求编号关联群号、QQ 号、命令和回执。可在 Bot 和 FastAPI 的配置中设 `MC_SUDO_ENABLED=false` 后重启两者。将 Mod 的 `sudo.enabled` 改为 `false` 后重启游戏服也可关闭游戏端指令通道。

## 本地测试和锁文件维护

开发环境按同一条 `pip install -r requirements.txt` 命令安装依赖。测试使用标准库 `unittest`、`sqlite3` 和临时文件，HTTP/WebSocket 测试直接调用 ASGI 应用。当前无需单独的开发锁、TestClient、httpx 或 pytest。

```sh
python -m unittest discover -s tests -v
```

[CI 工作流](../.github/workflows/ci.yml) 在 Ubuntu 上检查锁定依赖、Python 语法和回归测试，并另用 Java 17 构建和检查 JAR。Mohist 加载需在独立游戏测试服验证。

`requirements.in` 声明经过验证的直接依赖。`requirements.txt` 固定直接和间接依赖的版本，`colorama` 和 `win32-setctime` 带 Windows 条件。更新时先按需调整 `requirements.in`，再在干净的 Python 3.12 环境中解析。

```sh
resolve_report="$(mktemp)"
python -m pip install --dry-run --ignore-installed --report "$resolve_report" -r requirements.in
```

将报告中 `install` 记录的包名和版本整理为按包名排序的 `包名==版本` 条目，保留 `nonebot2[websockets]` 和平台条件。核对 Windows 与 Linux 的依赖差异，再从新锁文件安装，执行 `pip check` 和完整测试。解析报告只用于生成锁文件，放在临时位置。当前锁固定版本，软件包文件仍由包仓库提供。

## Minecraft 插件构建

使用 JDK 17 和 Maven。已验证 Maven 3.8.7，运行 `mvn -version` 可确认实际使用的 Java 版本。

```sh
cd minecraft-plugin
mvn -B -ntp clean verify
```

部署产物为 `minecraft-plugin/target/MCQQAuth-0.7.0.jar`。Spigot API 1.20.1 以 `provided` 作用域参与编译，由兼容的服务端提供。JAR 内置并隔离 Jetty、Gson 及所需的日志依赖。Maven 测试通过本机回环地址运行模拟 HTTPS/WSS 服务并生成临时证书，也会模拟 Bukkit 登录、入服、公告界面和踢出调度。

游戏名现支持 1–16 位，授权匹配忽略大小写。从要求游戏名为 3–16 位的版本升级时，先替换游戏服上的插件 JAR 并重启，再更新 Bot，避免旧插件收到短名后拒绝整份授权名单。若已有超过 16 位的绑定，升级前先处理这些记录。数据库校验会拒绝含有超长记录的库，插件也会拒绝含有超长名字的整份授权名单。无需迁移表结构。

Spigot API 使用 `1.20.1-R0.1-SNAPSHOT`，本次解析到 `1.20.1-R0.1-20230921.163938-66`。仓库可更新或清理快照，JAR 打包时间也会影响文件字节。若发布需要逐字节复现，应固定已验证的快照构件及其校验值、构建工具和归档时间，并在独立环境中重建比对。

### Minecraft 插件配置

首次启动会从 JAR 生成 `plugins/MCQQAuth/config.yml`，其中显式设置 `mode: OBSERVE`。升级时保留已有配置和 Token。旧配置缺少 `mode` 时继续使用 `ENFORCE`，因此升级后仍执行白名单。模式值允许大小写混用。配置文件无法读取，或顶层 `mode` 重复、无效时，插件记录错误并按未就绪的 `ENFORCE` 拒绝新登录。

| 配置 | 用途 |
| --- | --- |
| `mode` | `OBSERVE` 观察并提示，允许 MCQQAuth 判定为未授权或未知的玩家登录，也允许被撤权的玩家留在服内。`ENFORCE` 拒绝未授权和未知状态的新登录，按确认的快照执行在线撤权。 |
| `api.snapshot-url` | 默认 `https://auth.bf656.nightbugclub.cn/internal/mc/snapshot`，路径无末尾斜杠。 |
| `api.websocket-url` | 默认 `wss://auth.bf656.nightbugclub.cn/internal/mc/ws`，路径无末尾斜杠。 |
| `api.token` | 填写后端的 `MC_API_TOKEN`，两条连接均通过 Bearer 请求头鉴权。实际值只保存在面板服的运行配置中。 |
| `api.connect-timeout-ms`、`api.request-timeout-ms` | 默认 5000、8000 毫秒，分别限制连接与请求总耗时。 |
| `sync.reconnect-min-ms`、`sync.reconnect-max-ms` | 默认 1000、30000 毫秒。授权通道按这两个值指数退避，成功同步快照后恢复初始等待。 |
| `messages.unauthorized`、`messages.not-ready`、`messages.revoked` | 未授权拒绝、服务未就绪拒绝和撤销踢出提示。 |

连接使用 Java 信任库验证证书链和主机名。授权通道在收到连接确认或上一次匹配的 Pong 后等待 15 秒发送 Ping，Pong 超时为 10 秒。心跳超时后会断开并按退避策略重连。插件会在启动和重连后获取完整授权快照，并按更高版本提醒刷新。客户端最多接收 2 MiB 的快照。若恢复数据库备份导致版本回退，运行中的插件将保持未就绪，需在维护窗口先核实恢复后的名单和版本，再重新启动游戏服。

离线模式仅按服务端收到的游戏名比对授权名单。知道已绑定游戏名的人可以冒用它，QQ 绑定无法证明连接者就是该 QQ 账号的持有人。

#### 观察模式入服公告

```yaml
mode: OBSERVE

observe:
  notice:
    enabled: true
    show-delay-ticks: 30
    cooldown-seconds: 86400
    group-number: "123456789"
    title: "社区认证提醒"
    message:
      - "你的 Minecraft 游戏 ID 尚未完成社区认证。"
      - "请加入群 {group}，发送 /绑定 {player} 完成绑定。"
    confirm-button: "我知道了，继续游戏"
```

| `observe.notice` 配置 | 默认值与限制 |
| --- | --- |
| `enabled` | 默认 `true`。设为 `false` 时仍记录观察结果。 |
| `show-delay-ticks` | 默认 30 Tick，可设为 1–1200 的整数。 |
| `cooldown-seconds` | 默认 86400 秒（24 小时），可设为 0–2592000 的整数。`0` 允许下次连接再次展示。 |
| `group-number` | 用引号包裹实际 QQ 群号。接受 5–20 位十进制数字，首位须为 1–9。缺失或无效时显示「未配置」。 |
| `title`、`confirm-button` | 分别默认为「社区认证提醒」和「我知道了，继续游戏」，各限 32 个字符。 |
| `message` | 默认说明社区认证和 QQ 群绑定步骤，接受 1–6 行，每行最多 80 个字符。 |

标题、按钮和正文支持 `{player}`（当前游戏名）和 `{group}`（群号或「未配置」）。其他占位符按原文本显示，替换后的文字按上述长度截断。类型、范围或文案无效时使用相应默认值。公告由游戏服配置控制。

Bukkit 只能在玩家成功进入游戏后打开背包界面。名单未就绪时状态为 `UNKNOWN`，等待一次同步确认。只有有效名单确认玩家未授权才会尝试打开公告。延迟期间若玩家完成绑定并同步、离线、已有其他菜单或快照失效，本次跳过。按钮和 Esc 都可关闭窗口并继续游玩。点击确认只记录本次会话的确认，绑定仍由玩家在 QQ 群完成。

安装 AuthMe 时，若当前连接还未展示公告，AuthMe 登录或注册成功后会按同样的延迟重新检查一次。公告仍遵循授权状态、冷却和其他插件的菜单规则。AuthMe 为可选集成。

同一连接最多展示一次。窗口实际打开后，按小写游戏名计算冷却。**插件重启后，内存冷却记录会重置。** `/mcqqauth` 需要 `mcqqauth.admin` 权限，可查看当前模式、授权服务状态、最近确认版本与名单人数，以及入服公告是否启用。

### 在线切换准入模式

控制台和有 `mcqqauth.admin` 权限的管理员可执行以下操作。该权限默认授予 OP。

| 指令 | 行为 |
| --- | --- |
| `/mcqqauth mode` | 查询当前模式与生效的准入策略。 |
| `/mcqqauth mode observe` | 停止 MCQQAuth 登录拦截和在线撤权。后续入服按公告配置处理，其他插件的准入策略继续生效。 |
| `/mcqqauth mode enforce` | 对新登录执行授权检查。已在线的未授权或状态未知玩家获得当前连接的豁免，已授权玩家仍受后续撤权约束。 |
| `/mcqqauth mode enforce --kick-online` | 使用就绪的授权名单检查在线玩家，清除已检查会话的豁免，踢出未授权玩家并报告人数。已处于 `ENFORCE` 时也可执行。 |

切换无需重启 Mohist，现有 HTTPS/WSS 客户端继续同步。插件先原子保存 `config.yml` 中的模式，再应用到运行中，重启时读取已保存的值。若配置缺少 `mode`，可安全编辑的旧配置会在切换到 `OBSERVE` 时补上该字段。公告、Token、端点等设置仍在启动时读取。

会话豁免在玩家退出时清除，重新登录时按当前模式验证。后续确认该玩家获得授权时也会解除豁免。进入 `ENFORCE` 会清理等待中的公告和本插件自己的公告窗口，保留冷却时间。回到 `OBSERVE` 后，新入服玩家按正常流程触发公告。

使用 `--kick-online` 时，如果保存模式前授权未就绪，原模式继续生效。保存后若同步中断或名单等待刷新，在线清理会在下一次检查时中止，已保存的模式继续生效，指令会报告已完成的人数。

需要解除 MCQQAuth 准入拦截时，执行 `/mcqqauth mode observe`。已收到拒绝的客户端需要重新连接。若文件无法原子替换、权限不足或 `mode` 的 YAML 结构无法安全编辑，指令会报告失败。修复文件及目录权限，或将顶层字段整理为单独一行的 `mode: OBSERVE` 或 `mode: ENFORCE` 后重试。文件从无效状态修复后，如需重新读取其他设置并启动授权同步，请完整重启 Mohist。

### Mohist 面板服维护窗口

1. 使用 Java 17 和 Mohist 1.20.1 的独立测试服，配置隔离的 HTTPS/WSS 模拟授权端点、测试 Token 和测试游戏名。构建 JAR，备份待升级服的旧插件与配置。
2. 停服后将 `plugins/` 中的旧版替换为 `MCQQAuth-0.7.0.jar`，只保留一个 MCQQAuth JAR。保留已有 API 地址、Token 和文件访问权限。首次安装可从 JAR 提取默认配置，填写两条授权端点和 Token。
3. **阶段 A · 观察**。显式配置 `mode: OBSERVE` 与 `observe.notice.enabled: true`，填写 QQ 群号并启动游戏服。用 `/mcqqauth` 确认模式和同步状态。用已授权、未授权、同步暂不可用三种测试状态入服，核对公告、日志及同步恢复后的表现。检查原版客户端的中文文案、Esc、确认按钮、背包操作和其他插件的登录菜单。
4. **阶段 B · 强制**。保留已授权和未授权的测试玩家在线，执行 `/mcqqauth mode enforce`。验证新登录、在线豁免、退出重连和撤权。用隔离的测试名单演练显式清理，核对踢出人数和日志。在 WSS 断开时核对清理被拒、新登录被拒及重连后的恢复。确认切换本身未产生新连接，再重启测试服核对模式保存结果。
5. 生产环境先按观察阶段核对同步和公告，再用指令切换。修改公告等其他配置后，在维护窗口完整停止并重新启动 Mohist。完整回滚时停服，移出 `MCQQAuth-0.7.0.jar`，恢复同一次备份的旧 JAR 与配置，再启动并核对 `/mcqqauth`。
