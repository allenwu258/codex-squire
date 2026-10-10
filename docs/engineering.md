# 工程规范与验证

## 编码基线

参考 OOP 设计原则和 Alibaba P3C 的可读性、封装、异常处理、资源关闭和并发约束，不宣称完整通过所有 P3C 规则认证。

- Java 17 record 表达实体和 DTO；集合仍需防御性复制才能保证深度不可变。业务命名明确，常量/成员/方法命名受 Checkstyle 检查。
- 构造器注入；无字段注入、全局 Service Locator 或静态数据库连接。
- JDBC 参数化 SQL，短写事务、try-with-resources 关闭连接和语句。
- Controller 负责 HTTP 映射，路由编排在 GatewayService，SQL 在领域 Repository。
- 异常包含稳定 code，不把 SQL、API Key、提示词、上游原始异常消息输出到管理错误响应。
- 网络流程不调用 block；数据库在专用有界调度器执行。
- 并发安全依靠数据库事务和幂等状态转换，而不是先检查再修改的松散调用。
- TypeScript strict + noUnusedLocals/noUnusedParameters，API 响应类型显式定义。
- 编辑状态局限在对应表单，公共 UI 组件仅抽取 Modal、表单、复制、图标按钮等真实重复行为。
- 交互提供 loading、失败反馈、确认删除、空数据、不可执行状态和键盘焦点。
- 认证策略与标签页存储放在 `auth.ts`，启动和会话生命周期放在 `useWorkspaceSession`；领域页面只使用当前会话的 `ApiClient`。原生凭据不持久化，失效立即取消所有旧会话请求，不重放修改操作。

## 仓库约定

- `.gitattributes` 将文本规范为 LF，Windows `.bat` / `.cmd` 使用 CRLF；二进制资源不做换行转换。`.editorconfig` 定义 UTF-8、缩进和结尾换行。
- 提交 Gradle Wrapper（包括 wrapper JAR）、`gradle.lockfile`、`pnpm-lock.yaml`、配置 sample 和测试源码；`backend/gradlew` 保留可执行位。
- `.gitignore` 排除依赖、构建目录、运行数据、数据库及其 WAL/SHM、密钥/令牌、日志、Playwright 报告和 JVM dump。
- 根 `.env`、`.env.local` 等本机配置不提交；`.env.example` 与 `.env.*.example` 允许提交。新增配置键要同时更新 sample、共享校验和 [配置文档](configuration.md)。
- 不在 sample、截图、提交信息或测试输出中放实际 Provider Key、管理令牌或网关令牌。忽略规则不能替代对已暂存内容的检查。
- 提交前执行 `git diff --check`；暂存后用 `git diff --cached --check` 和 `git diff --cached --stat` 核对范围，不把本机 `.runtime` 探针作为唯一可复现测试证据。

## 本机验证入口

先复制并编辑 `.env.example` 为 `.env`，从项目根目录在 PowerShell 7 中执行：

```powershell
. .\scripts\Environment.ps1
$configuration = Read-SquireEnvironment -Path .env -ProjectRoot (Get-Location).Path
$null = Initialize-SquireJava $configuration
Initialize-SquireNode $configuration
.\scripts\tests\Environment.Tests.ps1

Push-Location backend
try { .\gradlew.bat check bootJar --offline --console=plain } finally { Pop-Location }

Push-Location frontend
try {
    pnpm install --frozen-lockfile
    pnpm build
    pnpm test:e2e
} finally { Pop-Location }
```

首次无 Gradle 缓存时去掉 `--offline`。E2E 使用当前 `JAVA_HOME` 和构建目录唯一的可执行 JAR，不再硬编码开发机 JDK 路径；使用独立临时数据和固定测试端口 8798/5188，不读取本机 Provider 凭据。运行前安装 Chrome 和可用的 Codex CLI，并确保测试端口空闲；修改了 JAR 版本而留下多个产物时先执行 `clean bootJar`。

`Environment.Tests.ps1` 不依赖 Pester，覆盖配置缺失、必填值、未知/重复键、引号、包含空格/等号的路径、项目根目录解析、非法端口、同端口、超时关系、字面值不执行、端口占用，以及两个真实入口缺少配置时的非零退出。使用临时文件和临时本机监听端口，不修改本机 `.env` 或运行数据。

## 自动检查

`gradlew check` 包括 JUnit 测试和 Checkstyle；`bootJar` 生成可执行 JAR。Java 编译启用 `-Xlint:all` 与参数名保留，源码 UTF-8。

当前 Checkstyle 检查：禁用星号导入、类型/方法/成员/参数/常量命名、控制块花括号、空语句、equals/hashCode 配对、每行一个语句、文件名一致性、Tab 和结尾换行。这只是自动化子集，架构审查与并发正确性主要由实现边界和行为测试保证。

前端 `pnpm build` 执行 TypeScript 检查及 Vite 生产构建。pnpm 只允许 esbuild 执行依赖安装脚本，其他新依赖的构建权限需要显式审核。

## 后端测试矩阵

| 范围 | 验证内容 |
| --- | --- |
| JSON 代理 | 请求/工具字段与响应原样保留，只调用一个成功来源 |
| SSE | 嵌套 response.usage、UTF-8 分包、CRLF、多行 data、超大事件后继续结算 |
| 用量可信度 | 忽略负数/无效/中间 usage；缓存不重复加总；无完成事件标记未完成 |
| 重试边界 | 429 转移；400/503 不重放；重定向拒绝 |
| 限额 | 请求日限额、Token 预留不足、16 线程执行 32 个争抢任务、跨 UTC 日期 |
| 生命周期 | 取消释放并发但保留未知占额；重复结算幂等 |
| 持久化 | 加密随机 nonce、密钥重启可解密、活动方案持久化、未完成请求恢复、迁移 checksum 校验 |
| 实例隔离 | 同一数据目录拒绝第二个后端拥有者 |
| 鉴权 | 管理/网关令牌不可互用、异域 Origin 拒绝 |
| 配置一致性 | API Key 留空保留、引用约束、成员更新失败事务回滚 |

测试使用 JDK/Netty 本机模拟服务和独立临时 SQLite 文件，不要求真实 Key，不调用付费 API。时区/跨日场景使用可注入 Clock，不依赖机器当前日期。

该矩阵包含单元和集成层级：超大事件、多行 data 在 UsageInspector 单元测试中覆盖，不能描述为这些场景都经过完整 HTTP 链路。当前加权集成测试只验证所有来源可用的 2:1；取消集成测试主要验证部分流取消后的释放，不覆盖 completed 后再取消。测试通过不表示 [已知问题](known-issues.md) 已修复。

## 浏览器闭环

Playwright 使用真实后端，覆盖：登录、创建两个来源、限额字段、均衡方案权重与上下移动、激活、网关测试、Token 可见性、日志详情/导出、编辑保留 Key、受引用删除失败、成员移除后删除成功、刷新持久化以及退出。

`auth.spec.ts` 另外覆盖本地自动认证、服务模式手动认证、普通浏览器与后端作用域、旧存储清除、失败重试、单次自动恢复、修改不重放、管理与网关 401 隔离、运行实例切换、迟到结果、拒绝重定向和暂时故障保留手动会话。其 Tauri 桥是模拟入口，实际 IPC 权限由桌面验收补足。

Windows 上的 `pnpm --dir frontend test:desktop-auth` 使用真实 release WebView2；`-- --development` 使用真实调试 WebView2 和已运行的 Vite。需要先初始化共享 Java / Node 环境并构建对应二进制，完整命令见 [桌面验收](desktop.md#桌面验收)。测试独立数据、端口和 WebView 目录，不修改用户数据或 Codex 配置；CDP 参数仅对测试启动的进程生效，产品配置没有开放调试端口。关闭测试窗口使用 OS 消息，不扩大应用 IPC 权限。

测试在 1440×1000 与 390×844 视口截图，检查页面宽度不超出视口，收集浏览器未处理异常。截图保存在 `frontend/test-results`，不包含真实凭据。

另有真实 Codex CLI 0.153.4 冒烟测试：忽略用户配置，以命令行临时指定网关，使用只读沙箱和 ephemeral 模式，连接本机模拟 SSE 上游；断言收到 `turn.completed`、助手文本 `OK`，且后端持久化的 Token 为输入 15 / 输出 2。本测试不调用付费模型、不要求真实 API Key。

## 手工验收建议

1. 用自己实际使用的 Provider 进行一次流式 Codex 对话，核对工具调用和 Token 对账。
2. 根据日常上下文规模设置单次预留 Token。默认 8192 只是可修改的初始预算，不等于模型上下文上限。
3. 对于不同上游的同名模型，确认工具、reasoning、service_tier 等字段兼容，再放在同一轮询方案内。
4. 停止后端后备份整个数据目录，再演练恢复。禁止仅复制数据库而丢失 master.key。

## 仍然存在的限制

- 没有长期性能压测、真正公网断链注入、服务商账单对账或完整 P3C 商业规则扫描。
- Token 限额是预留预算；实际超出预留时可能超过设置的日额度。
- 日志目前保留全部历史，未实现归档/保留天数；统计接口只提供 UTC 当日视图。
- 未实现 session affinity，因此不支持 `previous_response_id`，跨来源可能降低 prompt cache 命中。
- Web 开发服务不是公网生产托管方案。将来部署到远程需补 TLS、用户体系、权限和部署加固。
- Windows 便携式构建与 Tauri 应用命令 ACL 已实现并有本机验收；系统密钥库、跨平台安装升级、干净机器和完整发布流程仍待验收。

## 演进顺序

统一以 [已知问题与开发顺序](known-issues.md) 为准：当前已修复路由公平性、基础终态与错误分类、数据库迁移和密钥恢复。后续补齐生命周期竞态、供应商账单及多模型运行治理，再设计会话与提示词，最后落实 Tauri 生命周期。文档里的目标设计不能替代实现和回归证据。

## 验证记录

2026-09-11：初版提交 `8843388` 的业务实现已通过 22 项后端测试、Checkstyle、前端构建及 2 项 E2E（含 Codex CLI），测试在提交前执行，随后仅整理仓库规则与文件末尾空行。2026-09-10 的官方参考页面读取遇到 TLS 连接失败，此记录只说明当时证据限制，不是当前网络状态或协议兼容性结论。

共享环境配置改动基于 `d9fa2b9` 之后的工作区，按用户要求尚未提交；验证结果应与此工作区对应，不归因于归档文档的提交。环境为 Windows、GraalVM JDK 17.0.9、Node.js 24.20.0、pnpm 12.3.4。没有使用真实付费上游，也没有进行长期压测或桌面打包验收。

本轮验证：15 项环境/启动检查、22 项后端测试、Checkstyle、前端构建和 2 项 Playwright E2E 全部通过，CLI 版本为 0.153.4。两个独立脚本从项目目录外调用后成功加载根 `.env`；本机网页/后端实际启动，管理代理、数据目录、无令牌 401、非允许 Origin 403、经网页代理到网关的无效请求 400 均已核验，未发送付费请求。

2026-10-10 本地自动认证开发：实现已提交为 `b18e1ee`，并合入 `main`，纳入 `v0.2.3`。开发阶段的 49 项后端测试及 Checkstyle、17 项环境/启动检查、14 项 Rust 测试、Rust 格式检查、TypeScript / Vite 构建通过。浏览器原有及新增认证回归共 24 个用例全部通过，包含真实 Codex CLI 与本机模拟上游、冷却清理 401 无未处理拒绝。真实 Windows release 与 development WebView2 均验证本地免输入认证、刷新复用实例、自动凭据不进入存储、令牌读取/校验故障与重试、实际 Provider/Scheme 的 `If-Match` 更新、外部导航拒绝、服务模式拒绝原生凭据、公布地址与本机地址分离、普通 Chrome 的令牌登录和退出回收；development 还检查 Vite HMR 与相同地址的普通浏览器。没有访问真实付费上游或修改 Codex 文件。本机验收不代表跨平台或干净机器安装已完成。
