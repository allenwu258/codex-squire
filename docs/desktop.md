# 桌面客户端架构

## 目标

桌面版由 Tauri 负责应用窗口和运行时管理，Java 后端继续负责 Provider、Scheme、Responses 网关、额度、SQLite 和请求诊断，React 继续负责管理界面。Rust 不复制网关业务，也不直接操作 SQLite。

## 运行模式

### 本地模式

- Java 后端绑定 `127.0.0.1`；
- Tauri 加载内置 React 静态资源；
- 启动器动态选择后端端口；
- 后端就绪后才进入管理界面；
- 关闭 Tauri 时停止 Java 子进程。

### 服务模式

- Java 后端绑定 `0.0.0.0`；
- Rust 启动内置静态文件服务；
- 前端默认绑定 `127.0.0.1`，可以明确选择绑定 `0.0.0.0`；
- 后端和前端端口由启动器动态选择；
- 网关对外地址使用用户输入的主机名/IP，不公布 `0.0.0.0`。

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
  "mode": "local",
  "backendUrl": "http://127.0.0.1:49152",
  "gatewayUrl": "http://127.0.0.1:49152/v1",
  "webUrl": "http://tauri.localhost",
  "frontendBroadcast": false,
  "adminTokenPath": ".../admin.token"
}
```

`ApiClient` 接受可选的绝对后端 URL。浏览器开发模式不传该值，继续使用 Vite 的相对路径和代理；Tauri 模式使用启动器返回的 `backendUrl`，因此不依赖 Vite Proxy。

服务模式的静态服务器提供 `/__squire/runtime.json`。局域网浏览器打开前端时，React 先读取这个不包含密钥的运行时描述，再使用动态后端地址调用 Java API；开发环境读取不到该端点时自动回退到 Vite 代理。运行时端点不会返回 `admin.token` 路径或任何凭据。

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
Codex-Squire-0.1.0-windows-x64/
├── codex-squire-desktop.exe
├── runtime/bin/java.exe
└── resources/
    ├── backend.jar
    └── frontend/
```

发行包运行时按 exe/resource 目录查找 JRE、JAR 和前端资源，不要求设置 `JAVA_HOME`、`SQUIRE_BACKEND_JAR` 或 `SQUIRE_FRONTEND_DIST`。发布前仍必须在干净机器验证 WebView2、资源路径、数据目录、端口冲突和退出回收。

## 后续工作

当前客户端架构已具备第一版启动和服务模式骨架，但还需要：

- 随包提供或可靠发现 JRE，而不是依赖系统 `java`；
- Windows Job Object 单实例和进程树治理；
- 服务模式的管理面/网关面监听隔离；
- Tauri 原生凭据存储和管理令牌注入；
- 自动升级、数据目录迁移和崩溃诊断；
- 本地模式和服务模式的 Playwright/安装包验收。
