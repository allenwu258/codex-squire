# 桌面客户端架构

## 目标

桌面版由 Tauri 负责应用窗口和运行时管理，Java 后端继续负责 Provider、Scheme、Responses 网关、额度、SQLite 和请求诊断，React 继续负责管理界面。Rust 不复制网关业务，也不直接操作 SQLite。

Tauri 本地模式已支持自动认证，服务模式内置窗口和所有普通浏览器仍需管理令牌。完整契约、权限边界和开发验收方案见 [本地自动认证架构与开发方案](desktop-local-auth-design.md)。一键修改 Codex 配置尚未实现。

## 运行模式

### 本地模式

- Java 后端绑定 `127.0.0.1`；
- Tauri 加载内置 React 静态资源；
- 后端默认端口为 `18787`，用户可以修改，成功启动后保存；端口没有变化时不会重写配置文件，端口冲突明确报错，不自动换端口；
- 后端就绪后，通过受限原生 IPC 自动认证，再进入管理界面，无需输入管理令牌；
- 页面刷新先查询原生运行实例，重新取得内存会话，不重复启动 Java；
- 关闭 Tauri 时停止 Java 子进程。

### 服务模式

- Java 后端绑定 `0.0.0.0`；
- Rust 启动内置静态文件服务；
- 前端默认绑定 `127.0.0.1`，可以明确选择绑定 `0.0.0.0`；
- 后端和前端默认端口为 `18787` / `18788`，支持修改和保存；配置文件替换使用平台原子操作，Windows 下也支持覆盖已有文件；
- 配置读取失败时启动器保留可编辑输入，并提供恢复默认端口和重新读取入口。用户明确启动后才会尝试保存当前端口；端口持久化失败会终止本次启动并回收已启动的服务进程。
- 网关对外地址使用用户输入的主机名/IP，不公布 `0.0.0.0`。
- 内置窗口仍需管理令牌，退出管理只清理会话，服务继续运行。

服务模式是受信任局域网能力，不是公网部署方案。当前管理 API、网关 API 和 Connection 信息仍由同一个 Java 进程提供；没有 TLS、RBAC、用户隔离、登录失败限流或审计系统。启用局域网前端前必须确认网络边界，不能直接映射到公网。

## 进程生命周期

Rust `RuntimeManager` 管理以下状态：

```text
未启动 -> 启动 Java -> 等待 /api/health -> 启动服务前端 -> READY
READY -> 停止前端 -> 终止 Java 进程树 -> 已停止
```

Java 后端 stdout/stderr 写入数据目录的 `desktop-backend.log`。Windows 退出时使用 `taskkill /T /F` 回收 Java 子进程树；后续发布版应替换为 Job Object，以覆盖更严格的异常退出场景。

## 前端运行时契约

Tauri 启动器向页面提供：

```json
{
  "runtimeId": "opaque-instance-id",
  "mode": "local",
  "backendUrl": "http://127.0.0.1:18787",
  "gatewayUrl": "http://127.0.0.1:18787/v1",
  "webUrl": "http://tauri.localhost",
  "frontendBroadcast": false,
  "adminTokenPath": ".../admin.token"
}
```

`ApiClient` 接受可选的绝对后端 URL。浏览器开发模式不传该值，继续使用 Vite 的相对路径和代理；Tauri 模式使用启动器返回的 `backendUrl`，因此不依赖 Vite Proxy。

服务模式的静态服务器提供 `/__squire/runtime.json`。局域网浏览器打开前端时，React 先读取这个不包含密钥的运行时描述，再使用动态后端地址调用 Java API；开发环境读取不到该端点时自动回退到 Vite 代理。运行时端点不会返回 `admin.token` 路径或任何凭据。

原生窗口的 `backendUrl` 始终是自身 Java 后端的回环地址；公共描述的 `backendUrl` 和 `gatewayUrl` 使用公布地址。原生服务窗口可以连接本机后端，而远端浏览器不会误连浏览器所在机器的回环地址。

## 认证与权限

| 客户端 | 认证方式 | 刷新 | 退出管理 |
| --- | --- | --- | --- |
| Tauri `local` 内置窗口 | 原生自动取得并校验管理令牌 | 查询同一运行实例，重新认证 | 隐藏按钮；关闭应用停止运行时 |
| Tauri `service` 内置窗口 | 手动输入管理令牌 | 校验该后端的标签页会话 | 清理手动会话，服务继续运行 |
| 普通浏览器，包括 `127.0.0.1` / `localhost` | 手动输入管理令牌 | 校验该后端的标签页会话 | 清理手动会话 |

`get_local_admin_session` 只接受 `expectedRuntimeId`，从内部保存的真实数据目录读取 `admin.token`。命令检查 `main` 窗口、应用来源、存活子进程、本地模式和实例；读取上限为 4 KiB，拒绝空值、非 ASCII 和内部空白，不创建或改写令牌。

所有应用命令通过 `AppManifest` 生成显式权限，capability 仅允许本地 `main` 内容；窗口拒绝外部导航。开发构建额外接受精确 `devUrl` 来源，正式包不接受任意回环网页。没有通用文件读取、shell 或远端凭据 capability。

自动令牌只存在于会话和 `ApiClient` 内存，公共描述、全局运行时对象和浏览器存储没有自动凭据。手动令牌使用按后端 URL 隔离的 `sessionStorage`；Vite 代理会话按页面 Origin 隔离。旧 `squire.adminToken` 不迁移，升级后需要重新登录一次。

`useWorkspaceSession` 管理发现、认证、就绪、登录、停止和故障状态。切换会话立即取消旧请求，并用会话代次拒绝迟到结果。所有管理接口的 401 统一失效；本地会话最多自动恢复一次，修改请求不会重放。网关测试的 401 不影响管理会话。临时网络或存储错误不会删除有效的手动令牌。

本地认证失败提供重试，不能改为输入令牌。Java 继续要求 Bearer Token，没有回环免鉴权分支；允许的 CORS 请求头包括 `If-Match`。管理和网关 fetch 都拒绝 HTTP 重定向。

## 构建与开发

前置条件：

- Rust stable、Cargo；
- Tauri v2 所需的 Windows WebView2 和 C++ 构建工具；
- Java 17；
- Node.js 22.12+ 和 pnpm 12.3.4。

先构建前端和后端：

```powershell
cd backend
.\gradlew.bat clean bootJar

cd ..\frontend
pnpm install --frozen-lockfile
pnpm build
```

开发模式：

```powershell
cd frontend
pnpm tauri:dev
```

发布构建：

```powershell
cd frontend
pnpm tauri:build
```

本地开发时可以通过 `SQUIRE_BACKEND_JAR` 和 `SQUIRE_FRONTEND_DIST` 覆盖资源发现路径。生成不依赖系统 Java 的 Windows 便携式发布包：

```powershell
.\scripts\package-release.ps1
```

脚本会构建后端 JAR、前端 dist 和 Tauri release exe，使用 Java 17 `jlink` 生成随包 runtime，并固定生成以下目录：

```text
Codex-Squire-0.2.3-windows-x64/
├── codex-squire-desktop.exe
├── runtime/bin/java.exe
└── resources/
    ├── backend.jar
    └── frontend/
```

发行包运行时按 exe/resource 目录查找 JRE、JAR 和前端资源，不要求设置 `JAVA_HOME`、`SQUIRE_BACKEND_JAR` 或 `SQUIRE_FRONTEND_DIST`。发布前仍必须在干净机器验证 WebView2、资源路径、数据目录、端口冲突和退出回收。

## 桌面验收

初始化共享 Java / Node 环境、构建后端后，从项目根运行：

```powershell
pnpm --dir frontend tauri:build -- --no-bundle
pnpm --dir frontend test:desktop-auth
```

开发模式另外运行 Vite，构建不带 `custom-protocol` 的调试可执行文件，再执行：

```powershell
pnpm --dir frontend dev --port 5173
# 另一个终端，保留 Vite 运行：
cargo build --manifest-path src-tauri/Cargo.toml --locked --offline
pnpm --dir frontend test:desktop-auth -- --development
```

验收脚本使用真实 WebView2 和原生 IPC，创建独立 `.runtime/desktop-auth-*` 数据与 WebView 目录，临时启用仅供此进程使用的 CDP 端口。覆盖自动认证、刷新复用实例、来源/实例/模式拒绝、令牌文件故障与重试、实际跨域版本编辑、服务公布地址、普通 Chrome 登录和 OS 关闭窗口后的子进程回收。开发验收还覆盖相同 Vite 地址的普通浏览器及 HMR WebSocket。截图和安全诊断留在对应临时目录，不输出令牌，不访问付费上游。

普通 `test:e2e` 的原生桥模拟用于覆盖前端竞态；不能代替 `test:desktop-auth` 的原生权限验收。测试只覆盖当前 Windows 开发机，其他平台和干净机器安装仍需单独验证。

## 后续工作

当前客户端架构已具备第一版启动和服务模式骨架，但还需要：

- Windows Job Object 单实例和进程树治理；
- 服务模式的管理面/网关面监听隔离；
- 系统凭据库和密钥轮换；
- 本地模式一键修改 Codex 配置，独立设计文件预览、备份和恢复；
- 自动升级、数据目录迁移和崩溃诊断；
- 跨平台、干净机器与安装包验收。
