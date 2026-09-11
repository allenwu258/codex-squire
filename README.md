# Codex Squire

面向本地 Codex 的轻量 Provider 管理工作台。第一版采用 Java 17 + Spring WebFlux + SQLite 后端、React + TypeScript 网页端；两个进程独立启动。桌面端暂不实现，后续由 Tauri 封装现有前端并管理 Java 进程。

本目录为 Codex Squire 新版主线。旧版源码已归档至 `D:\Projects\deprecated-projects\codex-squire`，新旧实现的分析见 [版本对比](docs/legacy-comparison.md)。

## 已实现

- Provider 的创建、编辑、启停、删除；Base URL + API Key，远程地址要求 HTTPS。
- 多套路由方案，成员增删、顺序调整、权重编辑、原子切换生效方案。
- 按序使用、加权轮询；跳过禁用、并发满载、达到本地额度的来源。
- 本地 `POST /v1/responses`，普通 JSON 和 SSE 字节流转发；保留工具调用、reasoning 等请求字段。
- 每个 Provider 独立的每日请求上限、每日 Token 上限、最大并发、单次 Token 预留额。
- 输入、输出、缓存输入 Token 统计，以及用量缺失、取消、中断的独立标记。
- 按上游尝试记录的日志、分页、详情、JSON 导出。
- 独立管理令牌与网关令牌、AES-GCM 加密上游密钥、本机数据目录权限约束。
- Codex 配置片段、密钥复制、显式触发的连通性测试。

## 环境

| 项目 | 版本 / 路径 |
| --- | --- |
| Java | 17；默认 `C:\SDK\Java\17\graalvm-jdk-17.0.9` |
| Spring Boot | 3.5.16 |
| SQLite JDBC | 3.53.4.0 |
| Gradle | Wrapper 8.10.2，附下载 SHA-256 校验 |
| Node.js | 22.12+；本机已验证 24.20.0 |
| pnpm | 11+；锁定工具版本 12.3.4 |
| React / TypeScript | 18.3.1 / 5.7.2 |
| Vite | 6.4.3 |
| Shell | Windows PowerShell 7 |

依赖版本已固定。后端提交 `gradle.lockfile`，前端提交 `pnpm-lock.yaml`。初次启动需要下载依赖；本地缓存完整时后端可传 `-Offline`。

## 启动

在两个终端中分别执行：

```powershell
# 终端 1：编译并启动后端
cd D:\Projects\codex-squire
.\start-backend.ps1

# 终端 2：安装依赖并启动网页
cd D:\Projects\codex-squire
.\start-web.ps1
```

默认地址：后端 `http://127.0.0.1:8787`，网页 `http://127.0.0.1:5173`。端口占用时脚本选择后续空闲端口，以控制台输出为准。后端端口变化时，网页脚本传 `-BackendUrl http://127.0.0.1:实际端口`。

默认允许网页来源 `localhost` / `127.0.0.1` 的 5173、5174 端口。其他网页端口需要在后端启动时显式传入来源，例如：

```powershell
.\start-backend.ps1 -Port 8789 -AllowedOrigins 'http://127.0.0.1:5180'
.\start-web.ps1 -Port 5180 -BackendUrl http://127.0.0.1:8789
```

脚本不会启动另一个端，也不会结束占用端口的其他进程。按所在终端的 Ctrl+C 停止对应服务。已编译后可使用 `start-backend.ps1 -SkipBuild`，依赖已安装后可使用 `start-web.ps1 -SkipInstall`。

首次登录需要管理令牌，启动后在本机读取：

```powershell
Get-Content "$env:USERPROFILE\.codex-squire\admin.token"
```

默认数据目录是 `%USERPROFILE%\.codex-squire`。可用 `-DataDir` 指定另一个目录；令牌文件也位于该目录。**不要使用归档旧版的 `.codex-manager` 数据目录，两版数据库结构不兼容。**

## 完整使用流程

1. 在「来源管理」添加上游 API 的 Base URL 和 API Key。`https://api.example.com` 会请求 `/v1/responses`；`https://api.example.com/v1` 会请求 `/v1/responses`；自定义前缀 `https://host/proxy/v1` 会请求 `/proxy/v1/responses`。
2. 设置请求/Token 每日上限、并发数与单次预留 Token。上限为 `0` 表示不限，单次预留和并发必须大于零。
3. 在「配置方案」创建方案并加入来源。按序模式按排列顺序选择；均衡模式使用成员权重进行轮询。点击「启用方案」。
4. 在「Codex 接入」复制配置片段，合并到现有 `~/.codex/config.toml`，保留自己的模型和其他配置。不要整文件覆盖已有设置。
5. 将网关 API Key 设置为启动 Codex 的终端环境变量 `SQUIRE_GATEWAY_KEY`，再从同一终端启动 Codex。
6. 用上游实际支持的模型发送请求，在概览与请求记录中核对结果。网页中的「发送测试」也会调用实际上游并消耗额度，只有点击时才发送。

配置示例：

```toml
model_provider = "squire"

[model_providers.squire]
name = "Codex Squire"
base_url = "http://127.0.0.1:8787/v1"
env_key = "SQUIRE_GATEWAY_KEY"
wire_api = "responses"
requires_openai_auth = false
supports_websockets = false
```

```powershell
$env:SQUIRE_GATEWAY_KEY = Get-Content "$env:USERPROFILE\.codex-squire\gateway.token"
codex
```

模型名称不会被网关重写。所有可能被选中的来源都应支持该模型和相应的 Responses 字段。

## 限额与重试语义

- 每日窗口统一使用 **UTC**，请求归属开始时间所在日期。
- 每个上游尝试消耗一次本地请求额度，包括上游拒绝。一个网关请求发生故障转移时可能对应多个尝试。
- 准入先原子检查并发、请求额度和 Token 额度，再在 SQLite 中预留单次 Token；不会让并发请求绕过同一份预留额度。
- 上游报告 usage 后用实际 `input_tokens + output_tokens` 替换预留。缓存输入包含在输入中，不重复累计。
- 取消、网络异常、异常重启或缺失 usage 时保留预留占额，标记 `RESERVED`；实际输入/输出统计只计 `REPORTED` 数据。
- **Token 上限是本地准入预算，不是上游硬计费上限。** 若实际用量超过预留，最终占额可以超过日限额，后续请求会被拒绝。远端是否继续生成也不由断开本地连接保证。
- 默认仅对明确的 **429** 在尚未转发响应前尝试下一个来源。400/401/403/5xx、连接异常、SSE 中断不自动重放。3xx 不跟随，防止向其他地址转发密钥。
- 按序模式会跳过本地禁用/限额满的来源；均衡模式按权重确定首选，再按方案顺序尝试余下可用来源。每个来源在一次网关请求中最多尝试一次。

## 验证

```powershell
$env:JAVA_HOME = 'C:\SDK\Java\17\graalvm-jdk-17.0.9'
$env:PATH = "$env:JAVA_HOME\bin;$env:PATH"
cd backend
.\gradlew.bat check bootJar

cd ..\frontend
pnpm install --frozen-lockfile
pnpm build
pnpm test:e2e
```

端到端测试使用已安装的 Chrome 和 PATH 中的 Codex CLI，并自动启动独立后端/网页，使用 8798 / 5188 端口及 `.runtime/e2e-*` 临时数据。测试上游由测试进程在本机随机端口模拟，**不会调用付费 API**。已验证 Codex CLI 0.153.4 能通过网关完成 SSE 回合并结算用量；CLI 测试使用 ephemeral 模式和命令行配置覆盖，不修改用户 Codex 配置。

后端报告：`backend/build/reports/tests/test/index.html`。浏览器截图：`frontend/test-results/`。

## 范围与后续

第一版仅支持前台 Responses HTTP/SSE。暂不支持 OAuth/ChatGPT 账号、Chat Completions 转换、WebSocket、`/responses/compact`、后台任务、通过 `previous_response_id` 引用远程会话、按模型价格折算金额，以及会话/提示词管理。跨来源会话绑定、Tauri 进程管理、长期日志归档属于后续工作。

管理令牌保存在网页当前标签页的 `sessionStorage`，退出即清除；不会把上游 API Key 回显到网页。此版本定位单用户本地运行，不直接作为公网多人服务。

详细设计见 [架构说明](docs/architecture.md)、[接口说明](docs/api.md)、[工程规范与验证](docs/engineering.md)。
