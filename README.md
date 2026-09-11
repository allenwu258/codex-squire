# Codex Squire

面向本地 Codex 的轻量 Provider 管理工作台。第一版采用 Java 17 + Spring WebFlux + SQLite 后端、React + TypeScript 网页端；两个进程独立启动。桌面端暂不实现，后续由 Tauri 封装现有前端并管理 Java 进程。

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
| Java | JDK 17；在 `.env` 的 `JAVA_HOME` 中配置安装路径 |
| Spring Boot | 3.5.16 |
| SQLite JDBC | 3.53.4.0 |
| Gradle | Wrapper 8.10.2，附下载 SHA-256 校验 |
| Node.js | 22.12+；本机已验证 24.20.0 |
| pnpm | 11+；锁定工具版本 12.3.4 |
| React / TypeScript | 18.3.1 / 5.7.2 |
| Vite | 6.4.3 |
| Shell | Windows 上的 PowerShell 7 |

依赖版本已固定。后端提交 `gradle.lockfile`，前端提交 `pnpm-lock.yaml`。初次启动需要下载依赖；本地缓存完整时后端可传 `-Offline`。

## 配置与启动

克隆仓库后，先在项目根目录创建本机配置：

```powershell
git clone https://github.com/allenwu258/codex-squire.git
cd codex-squire
Copy-Item .env.example .env
```

编辑 `.env`：必须填写本机 JDK 17 的 `JAVA_HOME`，并确认端口和 `SQUIRE_DATA_DIR`。可选 `SQUIRE_NODE_HOME` 指向 Node.js/pnpm 所在目录，留空则使用 PATH。`.env` 不提交，仓库只保留 [.env.example](.env.example)。缺少文件、必填值或配置不合法时，脚本会报错提示配置，不会自动套用另一套环境。

两个终端都进入同一个项目根目录，先启动后端，再启动网页：

```powershell
# 终端 1：编译并启动后端
.\start-backend.ps1

# 终端 2：安装依赖并启动网页
.\start-web.ps1
```

sample 端口对应后端 `http://127.0.0.1:8787`、网页 `http://127.0.0.1:5173`。脚本从同一份文件推导后端代理地址及网页 Origin，不再自动更换被占用的端口。修改端口后请重启两端。网页启动前最多等待约 15 秒检查后端健康状态。

使用另一份环境文件时，两端传相同的 `-EnvFile`：

```powershell
.\start-backend.ps1 -EnvFile .env.local
.\start-web.ps1 -EnvFile .env.local
```

脚本不会启动另一个端，也不会结束占用端口的其他进程。按所在终端的 Ctrl+C 停止对应服务。已编译后可使用 `start-backend.ps1 -SkipBuild`，依赖已安装后可使用 `start-web.ps1 -SkipInstall`。

首次登录需要管理令牌，文件位于 `.env` 指定的 `SQUIRE_DATA_DIR` 下，后端控制台会打印完整路径。sample 使用 `.runtime/data`，可这样读取：

```powershell
Get-Content .runtime/data/admin.token
```

相对数据目录始终相对于项目根目录解析，也可指定绝对路径以继续使用已有数据。数据库、管理令牌、网关令牌和 `master.key` 必须作为一组保留，不要将不兼容应用的数据目录直接用于本项目。完整参数、配置语法及故障排查见 [环境与运行配置](docs/configuration.md)。

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
# 路径按实际 SQUIRE_DATA_DIR 调整；此处使用 sample 的目录。
$env:SQUIRE_GATEWAY_KEY = (Get-Content .runtime/data/gateway.token -Raw).Trim()
codex
```

模型名称不会被网关重写。所有可能被选中的来源都应支持该模型和相应的 Responses 字段。

## 限额与重试语义

- 每日窗口统一使用 **UTC**，请求归属开始时间所在日期。
- 每个上游尝试消耗一次本地请求额度，包括上游拒绝。一个网关请求发生故障转移时可能对应多个尝试。
- 准入先原子检查并发、请求额度和 Token 额度，再在 SQLite 中预留单次 Token；不会让并发请求绕过同一份预留额度。
- 上游报告 usage 后用实际 `input_tokens + output_tokens` 替换预留。缓存输入包含在输入中，不重复累计。
- 请求状态与用量口径独立。已采集可信 usage 时，即使随后取消也按真实用量结算为 `REPORTED`；未采集到 usage 且不是已识别的 4xx 拒绝时保留预留占额，标记 `RESERVED`。识别为 4xx 拒绝且无已采集 usage 时为 `NOT_APPLICABLE`、Token 占额为零。触发换源的 429 响应体当前不会解析 usage。
- **Token 上限是本地准入预算，不是上游硬计费上限。** 若实际用量超过预留，最终占额可以超过日限额，后续请求会被拒绝。远端是否继续生成也不由断开本地连接保证。
- 默认仅对明确的 **429** 在尚未转发响应前尝试下一个来源。400/401/403/5xx、连接异常、SSE 中断不自动重放。3xx 不跟随，防止向其他地址转发密钥。
- 按序模式会跳过本地禁用/限额满的来源；均衡模式按权重确定首选，再按方案顺序尝试余下可用来源。每个来源在一次网关请求中最多尝试一次。

## 验证

```powershell
. .\scripts\Environment.ps1
$configuration = Read-SquireEnvironment -Path .env -ProjectRoot (Get-Location).Path
$null = Initialize-SquireJava $configuration
Initialize-SquireNode $configuration
.\scripts\tests\Environment.Tests.ps1
cd backend
.\gradlew.bat check bootJar

cd ..\frontend
pnpm install --frozen-lockfile
pnpm build
pnpm test:e2e
```

端到端测试使用已安装的 Chrome、PATH 中的 Codex CLI 和已加载的 `JAVA_HOME`，自动启动独立后端/网页，固定使用 8798 / 5188 端口及 `.runtime/e2e-*` 临时数据。这两个测试端口应与本机服务端口错开。测试上游在本机随机端口模拟，**不会调用付费 API**。CLI 测试使用 ephemeral 模式和临时配置，不修改用户 Codex 配置。验证范围与日期见 [工程规范与验证](docs/engineering.md)。

后端报告：`backend/build/reports/tests/test/index.html`。浏览器截图：`frontend/test-results/`。

## 范围与后续

第一版仅支持前台 Responses HTTP/SSE。暂不支持 OAuth/ChatGPT 账号、Chat Completions 转换、WebSocket、`/responses/compact`、后台任务、通过 `previous_response_id` 引用远程会话、按模型价格折算金额，以及会话/提示词管理。跨来源会话绑定、Tauri 进程管理、长期日志归档属于后续工作。

管理令牌保存在网页当前标签页的 `sessionStorage`，退出即清除；不会把上游 API Key 回显到网页。此版本定位单用户本地运行，不直接作为公网多人服务。

当前加权调度在来源不可用时可能偏斜，完成后取消也存在状态分类限制；详情及修复顺序见 [已知问题与开发顺序](docs/known-issues.md)。

详细设计见 [架构说明](docs/architecture.md)、[接口说明](docs/api.md)、[环境与运行配置](docs/configuration.md)、[工程规范与验证](docs/engineering.md)。
