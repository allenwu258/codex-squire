# 环境与运行配置

## 单一配置入口

项目支持 Windows PowerShell 7。`start-backend.ps1` 和 `start-web.ps1` 默认读取项目根目录 `.env`，共用 `scripts/Environment.ps1` 的解析和校验。仓库提供 `.env.example`；首次使用须复制并编辑，尤其是 sample 中故意留空的 `JAVA_HOME`。

`.env` 与 `.env.local` 等本机环境文件由 Git 忽略，`.env.example` 可提交。这里配置的是运行环境，不放 Provider API Key 或管理令牌；凭据由应用在数据目录中管理。

| 键 | 是否必填 | 规则 |
| --- | --- | --- |
| `JAVA_HOME` | 是 | JDK 17 的绝对目录；后端启动检查 `bin/java.exe` 及版本 |
| `SQUIRE_NODE_HOME` | 否 | Node.js/pnpm 所在绝对目录；空值或省略时使用 PATH |
| `SQUIRE_PORT` | 是 | 后端端口，1024..65535 |
| `SQUIRE_WEB_PORT` | 是 | 网页端口，1024..65535，不能等于后端端口 |
| `SQUIRE_BIND_ADDRESS` | 否 | 后端监听地址，默认 `127.0.0.1`；服务模式使用 `0.0.0.0` |
| `SQUIRE_PUBLIC_BASE_URL` | 否 | Connection 页面公布的后端根 URL；不得填写 `0.0.0.0` |
| `SQUIRE_WEB_BIND_ADDRESS` | 否 | Vite/服务前端监听地址，默认 `127.0.0.1` |
| `SQUIRE_ALLOWED_ORIGINS` | 否 | 逗号分隔的浏览器 Origin；服务模式广播可显式使用 `*` |
| `SQUIRE_DATA_DIR` | 是 | 数据目录；相对路径以项目根目录为基准 |
| `SQUIRE_UPSTREAM_IDLE_SECONDS` | 是 | 上游读空闲超时，1..86400 秒 |
| `SQUIRE_UPSTREAM_TOTAL_SECONDS` | 是 | 单次上游尝试总超时，1..86400 秒，不能小于读空闲超时 |

两个超时不代表整个网关请求的共同截止时间；发生 429 换源时，每次 attempt 重新计时。连接超时仍由实现固定为 10 秒，请求体上限仍为 8 MiB。

## 文件语法

使用受限的 `KEY=value` 格式，不执行 PowerShell，也不使用 shell 求值：

```dotenv
JAVA_HOME=C:\SDK\Java\17\graalvm-jdk-17.0.9
SQUIRE_NODE_HOME=C:\SDK\NodeJS
SQUIRE_PORT=8787
SQUIRE_WEB_PORT=5173
SQUIRE_BIND_ADDRESS=127.0.0.1
SQUIRE_PUBLIC_BASE_URL=
SQUIRE_WEB_BIND_ADDRESS=127.0.0.1
SQUIRE_ALLOWED_ORIGINS=
SQUIRE_DATA_DIR="C:\Users\YourName\.codex-squire"
SQUIRE_UPSTREAM_IDLE_SECONDS=120
SQUIRE_UPSTREAM_TOTAL_SECONDS=1800
```

允许空行、以 `#` 开头的整行注释，以及完整的单引号/双引号包裹值。反斜杠、等号、`$` 等在值中按字面处理，不做转义或变量替换；`%USERPROFILE%`、`$env:USERPROFILE`、`~` 不会展开。不要使用行尾注释、`export` 或多行值。键区分大小写；未知键、重复键、缺失必填项、非法数值都会导致启动失败。

此文件是两个脚本的配置来源：父进程已有的 `JAVA_HOME`、`SQUIRE_PORT` 等不会覆盖文件中的对应值。脚本只设置自身及子进程的环境，不修改系统环境变量。Node/pnpm 未指定目录时仍从现有 PATH 查找。

## 启动参数

```powershell
# 从项目根目录执行。两个命令分别在独立终端中运行。
.\start-backend.ps1 -EnvFile .env
.\start-web.ps1 -EnvFile .env
```

`-EnvFile` 可用绝对路径；相对路径按项目根目录解析，与调用者当前目录无关。数据目录也按项目根目录解析，而不是环境文件所在目录。

- 后端：`-SkipBuild` 使用已有 JAR；`-Offline` 让 Gradle 仅使用缓存依赖。
- 网页：`-SkipInstall` 跳过依赖安装；默认执行 `pnpm install --frozen-lockfile`。
- 旧的 `-JavaHome`、`-Port`、`-DataDir`、`-AllowedOrigins`、`-BackendUrl` 参数已移除，请编辑共享文件。
- 后端脚本要求 `backend/build/libs` 中只有一个 `codex-squire-*.jar` 可执行包，忽略 `*-plain.jar`。版本升级留下多个 JAR 时先执行 `gradlew.bat clean bootJar`。

后端固定监听回环地址。网页代理地址自动为 `http://127.0.0.1:<SQUIRE_PORT>`；后端允许的 Origin 自动为 `http://127.0.0.1:<SQUIRE_WEB_PORT>` 和 `http://localhost:<SQUIRE_WEB_PORT>`。不另设一组可相互矛盾的 URL/Origin 配置。

端口被占用时脚本停止并提示，既不杀死其他进程，也不自动递增端口。修改任何端口后同时重启两端。网页启动前检查后端 `/api/health`，最多等待约 15 秒；失败时提示先启动使用同一份环境文件的后端。预检查不能消除端口竞争，最终仍以服务实际绑定结果为准。

两个脚本各自以前台方式运行一个服务；Ctrl+C 停止对应服务。后端可独立供 Codex 使用，不依赖网页一直在线。

## 数据与恢复

sample 默认使用 `.runtime/data`。已有安装可在本机 `.env` 中指定原数据目录的绝对路径，例如 `C:\Users\YourName\.codex-squire`，无需复制或重置数据库。登录令牌位置以启动输出的 `Admin token file` 为准，尤其是配置自定义目录时。

数据目录包含 `squire.db`、可能存在的 WAL/SHM 文件、`master.key`、`admin.token`、`gateway.token` 和实例锁。更换目录相当于切换独立工作空间，不会自动迁移配置。

备份前停止后端，完整备份数据目录，恢复时使用兼容版本。数据库迁移会记录版本和 checksum；升级前仍应保留完整备份。不要删除主密钥来重置应用：如果数据库中已有加密 Provider，后端会拒绝启动并要求从备份恢复。只复制 `squire.db` 而遗漏 `master.key`、WAL 或令牌文件，不能恢复一个可用工作空间。

## 故障排查

| 提示或现象 | 处理 |
| --- | --- |
| `Configuration file missing` | 复制 `.env.example` 到 `.env`，填写环境；两端确认同一 `-EnvFile` |
| `Configure JAVA_HOME` | sample 未配置；填写本机 JDK 17 绝对目录 |
| JDK/Node/pnpm 找不到或版本不符 | 检查安装目录；Node 需 22.12+，pnpm 推荐锁定版本 12.3.4 |
| `Port ... is in use` | 找到占用服务，或改共享文件中的端口并重启两端 |
| `Backend not ready` | 检查后端终端；端口、数据目录锁、JAR 和依赖错误都可能阻止启动 |
| 401 | 使用数据目录中的管理令牌登录；网关令牌不能登录管理面 |
| 403 `ORIGIN_DENIED` | 使用配置的本机网页地址；修改端口后重启后端 |
| 503 `NO_ACTIVE_SCHEME` | 先添加来源、创建方案并激活 |
| 503 `NO_AVAILABLE_PROVIDER` | 检查启用状态、并发、日限额和单次预留；预留高于剩余额度会拒绝准入 |
| 数据目录被占用 | 同一目录仅允许一个后端实例，确认另一进程已停止 |

直接运行 JAR、`gradlew` 或 `pnpm` 不会自动读取根 `.env`。开发检查按 [工程文档](engineering.md) 加载配置。后端 `application.yml` 的默认值保留用于独立启动和隔离测试，不是脚本缺少 `.env` 时的替代配置。
