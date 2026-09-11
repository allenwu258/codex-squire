# Codex Squire 新旧版本架构与实现对比

审阅日期：2026-09-10。旧版：`D:\Projects\deprecated-projects\codex-squire`；新版：`D:\Projects\codex-squire`。

路径于 2026-09-11 随主线重命名同步更新；以下实现结论与测试结果仍对应 2026-09-10 的审阅快照。

本文依据两份工作目录的实际源码、SQL、启动脚本、前端和测试编写。没有修改应用实现，没有操作真实 Provider、真实业务数据库或付费上游。本次新增的 Java 探针只使用临时数据库、模拟依赖和本地模拟 HTTP 服务。

## 1. 核心判断

**建议保留新版作为演进基础，定向吸收旧版的领域模型、可观测性、数据库迁移和边界测试经验，不建议整体回搬旧版网关。**

两版不是两种根本不同的技术架构，而是同一种“WebFlux 网络代理 + 阻塞式 SQLite JDBC + React 管理界面”的不同实现。新版在本地访问控制、原子准入、额度预留、SSE 增量解析和 CRUD 一致性上更扎实；旧版在来源运行状态、多窗口限额、请求诊断、迁移历史以及完成/取消竞争处理上更有积累。

同时，新版仍是第一版闭环，并未达到长期产品所需要的完整工程成熟度。尤其不能把“代码更短”“依赖更少”“Checkstyle 通过”等同于架构全面优于旧版或完整符合 Alibaba P3C。

### 1.1 最需要关注的发现

以下按影响排序；“复现”表示本次有独立探针输出，“源码确认”表示控制流和配置可直接确认，但没有对真实运行中的业务服务发起验证。

| 优先级 | 版本 | 发现与具体影响 | 证据 |
|---|---|---|---|
| P0 | 旧版 | 默认监听 `0.0.0.0`，管理接口没有接入 `admin-key` 验证；网络可达时，非浏览器客户端可以读取网关访问凭据、修改配置。CORS 无法替代鉴权 | 源码确认：[监听配置](/D:/Projects/deprecated-projects/codex-squire/backend/src/main/resources/application.yml:3)、[ProviderController](/D:/Projects/deprecated-projects/codex-squire/backend/src/main/java/com/codexmanager/provider/ProviderController.java:16)、[GatewayAccessService](/D:/Projects/deprecated-projects/codex-squire/backend/src/main/java/com/codexmanager/gateway/GatewayAccessService.java:20) |
| P0 | 旧版 | 加密主密钥有公开固定默认值；未覆盖环境变量时，拿到数据库的人可以按已知密钥解密 | 源码确认：[SecretCodec](/D:/Projects/deprecated-projects/codex-squire/backend/src/main/java/com/codexmanager/common/SecretCodec.java:23) |
| P0 | 旧版 | 方案更新先改名称、删除原成员，再校验/写入成员；中途失败不会回滚，导致已有配置丢失 | 复现：成员数 `1 -> 0`，名称也已经改变；[SchemeRepository](/D:/Projects/deprecated-projects/codex-squire/backend/src/main/java/com/codexmanager/scheme/SchemeRepository.java:40) |
| P0 | 旧版 | 自定义 DataSource 未绑定 Hikari 专属配置，声明的连接池、初始化 SQL 没有按预期生效，外键实际关闭 | 复现：pool=10、timeout=30000、initSql=null、foreign_keys=0；[DatabaseConfig](/D:/Projects/deprecated-projects/codex-squire/backend/src/main/java/com/codexmanager/config/DatabaseConfig.java:13) |
| P1 | 新版 | 加权分配先选择全部成员，再在准入时跳过不可用来源，导致剩余来源承接失效权重，严重失衡 | 复现：A/B/C 权重 100/1/1，A 不可准入，102 次选择为 B=101、C=1；[RoutingService](/D:/Projects/codex-squire/backend/src/main/java/dev/squire/gateway/RoutingService.java:24) |
| P1 | 新版 | 上游完成后客户端取消，已采集 usage 保留，但状态统一覆盖为 `CANCELLED`；不能从单一状态准确还原上游结果 | 复现：completed 后取消得到 `CANCELLED`、29 tokens；[GatewayService](/D:/Projects/codex-squire/backend/src/main/java/dev/squire/gateway/GatewayService.java:76) |
| P1 | 旧版 | 限额检查、并发获取、请求记录没有统一原子预留；并发请求可同时通过相同剩余额度检查 | 源码确认：[LimitEvaluator](/D:/Projects/deprecated-projects/codex-squire/backend/src/main/java/com/codexmanager/limit/LimitEvaluator.java:15)、[ConcurrencyRegistry](/D:/Projects/deprecated-projects/codex-squire/backend/src/main/java/com/codexmanager/gateway/ConcurrencyRegistry.java:16) |
| P1 | 旧版 | 并发 Semaphore 的许可数首次创建后不随配置更新；提高限额仍可能只允许原来的并发量 | 复现：配置由 1 增至 3，第二个许可仍获取失败；[ConcurrencyRegistry](/D:/Projects/deprecated-projects/codex-squire/backend/src/main/java/com/codexmanager/gateway/ConcurrencyRegistry.java:18) |
| P1 | 旧版 | 网关密钥 rotate 只清缓存再读取原值，实际没有轮换 | 复现：`rotationChanged=false`；[GatewayAccessService](/D:/Projects/deprecated-projects/codex-squire/backend/src/main/java/com/codexmanager/gateway/GatewayAccessService.java:35) |
| P1 | 旧版 | 多种 HTTP 错误均进入后续来源尝试，未使用声明的最大重试次数，可能掩盖配置错误并增加重复执行/计费风险 | 源码确认：[GatewayService](/D:/Projects/deprecated-projects/codex-squire/backend/src/main/java/com/codexmanager/gateway/GatewayService.java:283) |
| P1 | 新版 | 只有手写 V1 初始化，没有正式增量迁移历史、校验和机制；未来升级限额/日志结构时容易变成数据演进障碍 | 源码确认：[Database](/D:/Projects/codex-squire/backend/src/main/java/dev/squire/persistence/Database.java:102) |

P0 表示不应以现状作为正式本地产品的默认实现；对旧版风险的判断不代表本机当前端口一定可从局域网访问，实际可达性仍受防火墙和启动参数影响。

### 1.2 必须纠正的旧版印象

当前旧版已经有后续修复，不能继续用早期原型的结论评价它：

- `GatewayService` 已通过 `Mono<Boolean>` / `thenReturn(true)` 表示成功结束，成功请求不会仅因 `Mono<Void>` 空结果继续遍历全部来源。
- 上游响应体在 `exchangeToMono` 生命周期内消费，不能再认定当前实现必然在消费前释放响应体。
- SSE 捕获保存的是最后 1 MiB，不是最前面 1 MiB；长流尾部 usage 的保留能力已有改善。
- 已解析嵌套 `response.usage`，支持 completed/failed/incomplete 终态。
- 已有请求开始记录、幂等终态写入、取消补偿、启动恢复，以及 V2/V3 数据修复迁移。
- 当前后端测试是 14 项，本次全部通过；不能将旧版描述为只有三项简单测试。

## 2. 审阅与验证范围

阅读覆盖后端配置、安全、Provider、方案、路由、限额、Usage、请求日志、持久化、异常处理与测试；前端 API 封装、主要功能组件、状态流转；两版启动脚本、依赖配置，以及旧版 Tauri Rust/配置文件。

本次重新执行：

| 验证 | 结果 | 说明 |
|---|---|---|
| 旧版 `gradlew.bat test --offline --rerun-tasks --console=plain` | 14/14 通过 | 使用指定本地 GraalVM JDK 17.0.9 |
| 新版 `gradlew.bat check --offline --rerun-tasks --console=plain` | 22/22 通过，Checkstyle 通过 | 网关集成 15、持久化/配额 3、解析器 4 |
| 旧版 `pnpm build` | 通过 | TypeScript + Vite，实际构建使用 Vite 6.4.3 |
| 新版 `pnpm build` | 通过 | TypeScript + Vite 6.4.3 |
| 旧版真实配置/仓储临时探针 | 复现四类问题 | Hikari/PRAGMA、密钥轮换、方案非原子更新、Semaphore 容量 |
| 新版真实网关/路由临时探针 | 复现两类问题 | 完成后取消分类、不可用高权重成员导致偏斜 |

探针：[LegacyProbe.java](/D:/Projects/codex-squire/.runtime/review/LegacyProbe.java)、[NewGatewayProbe.java](/D:/Projects/codex-squire/.runtime/review/NewGatewayProbe.java)。新版探针使用真实 `GatewayService` / `RoutingService`、本地 Netty 上游和模拟仓储，验证结算参数与路由顺序；它不是对真实数据库的全链路压力测试。

本次没有进行真实供应商扣费验证、长时间压力测试、Tauri 编译/安装包测试，也没有重新运行浏览器 E2E 或原生 Codex CLI 测试。当前环境未发现 `cargo` 命令，桌面部分结论限定为静态代码审阅。两版前端构建通过不能替代交互验收。

## 3. 架构总览

### 3.1 共同基础

两版都选择 Java 17、Spring Boot WebFlux、Reactor Netty、SQLite、React 18、TypeScript、Vite。HTTP 流式传输是异步的，但 JDBC 仍是阻塞的。因此正确称谓是“异步网络 + 隔离阻塞持久化”，而不是全链路响应式数据库架构。

这对单机 Codex Provider 网关是合理取舍：部署简单、无需额外数据库服务、SQLite 事务足以承担配置和低至中等并发请求记账。升级为分布式网关之前，没有必要为了形式统一而引入不成熟的 SQLite 响应式驱动或额外中间件。

### 3.2 两版分层的真实区别

| 层面 | 旧版 | 新版 | 判断 |
|---|---|---|---|
| 后端版本 | Boot 3.3.13；SQLite 依赖声明 3.45.3.0，可外置 jar | Boot 3.5.16；SQLite 3.53.4.0；依赖锁定 | 新版构建输入更明确；版本更新本身不等于业务正确 |
| 功能包 | provider、scheme、limit、usage、requestlog、gateway 等 | provider、scheme、gateway、usage、persistence、security、api | 旧版领域概念更细，新版边界更少 |
| HTTP 层 | 多个领域 Controller | 一个 AdminController 汇总管理功能 + GatewayController | 新版对当前规模够用，业务扩展后应按领域拆分 |
| 业务层 | ProviderService、SchemeService 等显式存在 | 部分业务规则直接在 Repository，RoutingService 做编排 | 新版并非严格的 Controller-Service-Repository 分层 |
| 持久化 | JdbcTemplate + Hikari + Flyway | DriverManager 每次建连接 + 自有事务封装 + V1 SQL | 旧版工具选型可借鉴；新版事务用法更正确 |
| 阻塞隔离 | 共享 `Schedulers.boundedElastic()` | 专用 `sqlite` boundedElastic，4 线程、每线程队列上限 1024 | 新版隔离性更好，但仍需过载策略和监控 |
| 网关编排 | 871 行 GatewayService，较多重复参数和分支 | 184 行 GatewayService，AttemptContext + usingWhen | 新版更便于理解生命周期，仍有终态分类缺口 |
| 数据结构 | 已使用 record，不是全套可变 JavaBean | record + final 较一致 | 不应把使用 record 视为新版独有优点 |
| 前端 | 大量功能集中 App.tsx，单行 JSX 很长 | features 拆分 + 公共组件 + ApiClient | 新版可维护性更好，服务端状态并发控制仍需补齐 |
| 桌面 | Tauri 骨架，有生命周期和打包问题 | 当前明确不实现，后续封装 | 新版的范围控制符合当前需求，不算功能回退缺陷 |

### 3.3 一次请求的链路

旧版：

```text
Codex -> GatewayController 校验网关 key
      -> RoutingEngine 读取方案/Provider/滚动限额/并发快照
      -> GatewayService 逐个 Candidate
      -> 获取 Semaphore -> 写 IN_PROGRESS
      -> WebClient -> JSON 聚合 或 SSE 转发并捕获尾部
      -> UsageParser -> 更新 Provider 状态 -> 幂等终态日志
      -> 释放 Semaphore
```

新版：

```text
Codex -> AccessFilter 校验 gateway token / Origin
      -> 请求体校验 -> RoutingService 生成成员顺序
      -> BEGIN IMMEDIATE
           读取 Provider 最新配置
           检查并发、当日请求量、当日 token 占用
           插入 IN_FLIGHT + RESERVED
         COMMIT
      -> usingWhen 管理 AttemptContext
      -> WebClient -> 原始字节转发 + UsageInspector 增量观察
      -> 成功/异常/取消清理 -> 幂等结算 attempts
      -> 仅符合策略时尝试下一个来源
```

**最有价值的架构变化是把“能否发起这次请求”变成可持久化的原子准入，而不是单纯把类拆小。**

## 4. 能力矩阵：实现程度与语义差异

| 能力 | 旧版现状 | 新版现状 | 演进建议 |
|---|---|---|---|
| BaseURL + API Key | 实现；额外 responsesPath | 实现；URI 校验、路径前缀保留 | 保持单一明确的 BaseURL 约定 |
| `/v1/responses` JSON/SSE | 实现；JSON 与流的终态判断不完全一致 | 实现；增量观察、逐块 flush | 继续以 Responses 作为唯一正式协议 |
| `/v1/responses/compact` | 有路由，但仍走普通 responses 逻辑 | 未提供 | 未完成 compact 语义前不应宣称兼容 |
| 模型列表 | 由 defaultModel 汇总，缺省回退 gpt-5 | 未实现模型发现 | 配置模型列表与真实上游能力分开 |
| 多来源、多方案 | 后端模型较全；前端编辑能力有限 | CRUD、成员排序/权重、激活闭环 | 以新版交互为基础扩展 |
| 按序路由 | 实现 | 实现 | 增加跳过原因和总尝试预算 |
| 负载均衡 | 轮询；weight 字段未进入算法 | 权重生效；不可用成员导致偏斜 | 以有效候选集计算权重 |
| 方案成员启停/并发覆盖 | 模型及路由存在 | 暂无 | 根据实际使用频度补充，不必一次全加 |
| 限额规则 | 多条规则、多窗口、多指标 | 固定 UTC 当日请求/token 限额 | 继承规则模型，保留新版预留结算机制 |
| token 不确定性 | 有 usageSource/estimated，但估算不能完整覆盖总量 | actual 与 RESERVED 明确分开 | 保留来源可信度，补计费单位和调整审计 |
| 来源故障状态 | 失败数、最后错误、30 秒冷却 | 无持久化健康状态/冷却 | 增加可解释的运行状态与 Retry-After |
| 健康检查 | 手动 GET models；自动开关未实际驱动任务 | 全网关 Responses 测试 | 增加单来源 Responses 探测 |
| 日志诊断 | 首包/总耗时、字节数、reasoning、会话线索、错误码 | 核心状态、usage、Provider 名称快照、分页 | 合并双方优点 |
| 会话管理 | 只记录 session_key，未形成管理能力 | 暂无 | 两版都不应视为已有会话管理 |
| 提示词管理 | 未形成当前闭环 | 暂无 | 后续独立领域 |
| 安全管理 | admin-key 字段未落实；固定默认主密钥 | 独立随机令牌、ACL、loopback、Origin 校验 | 新版为基线，补轮换和恢复 |
| 数据迁移 | Flyway V1/V2/V3 | 手写 V1 + user_version | 优先恢复正式迁移机制 |
| 独立启动 | 后端/Web/桌面脚本，有工具链兜底 | 后端/Web 两个脚本、参数化端口/目录 | 保留双脚本，补发现/就绪机制 |

字段存在不代表能力完整。旧版 `healthCheckEnabled`、`maxRetryAttempts`、`recordRequestBody` 及 `usage_buckets` 没有形成对应的有效执行链路；新版暂未实现某些能力时，反而应继续保持接口和文案诚实。

## 5. Provider 与方案管理

### 5.1 Provider：配置状态与运行状态应该分离

旧版 Provider 包含默认模型、优先级、权重、并发数、启用状态、健康检查开关、运行状态、冷却时间、失败计数、最后错误、密钥指纹和时间戳。它已经意识到来源管理不仅是保存 URL 和密钥，还需要回答“现在为什么不能用”。

但旧版把很多职责堆在同一个模型中，部分字段语义重叠：Provider 的 priority/weight 与方案成员 sortOrder/weight 并存；status 与 enabled/cooldownUntil 可以不一致；健康检查开关也没有对应后台任务。

新版 Provider 精简到静态配置、日限额和预留参数，数据模型更直接，但无法展示最近的真实健康证据。来源 enabled 只意味着用户允许使用，不能推断来源在线或 Responses 可用。

建议后续形成三个明确对象：

- `ProviderConfig`：身份、BaseURL、凭据引用、启用状态、配置版本。
- `ProviderRuntimeState`：最近成功时间、最近错误码、连续失败次数、冷却截止时间。
- `SchemeMember`：顺序、权重、成员启用，以及确有需求时的覆盖策略。

配置修改与运行时状态更新应避免互相覆盖。来源历史记录保留名称快照，新版已做到；密钥格式版本和非明文指纹则可以吸收旧版实践。

源码：[旧 ProviderService](/D:/Projects/deprecated-projects/codex-squire/backend/src/main/java/com/codexmanager/provider/ProviderService.java:36)、[新 ProviderRepository](/D:/Projects/codex-squire/backend/src/main/java/dev/squire/provider/ProviderRepository.java:56)。

### 5.2 BaseURL、模型和探测的契约

旧版的 `responsesPath` 提供灵活性，但容易与 BaseURL 中的 `/v1` 重复拼接；手动探测更是固定使用 `baseUrl + /v1/models`，没有与实际 Responses 路径统一。

新版对 URL 使用 URI 解析，限制远程明文 HTTP，保留路径前缀；根地址补 `/v1/responses`，非根地址补 `/responses`。这比随意字符串拼接可控，但需要继续坚持文档约定：填写的是 API BaseURL，不是完整 `/responses` URL。

旧版 `defaultModel` 被路由器作为严格模型白名单使用。例如来源填写默认模型 A，请求模型 B，即使上游支持 B，也会被直接排除。这不是“默认值”的正常语义。以后应明确区分：

- `defaultModel`：调用方未指定模型时的缺省值，是否支持由 API 契约决定。
- `allowedModels`：明确的路由匹配条件。
- `modelMapping`：逻辑模型名到供应商模型名的显式映射。

当前新版要求请求带 model，是清晰且可接受的第一版策略。没有必要为模仿旧版而添加含混字段。

旧版 GET `/models` 通过只能证明该接口可访问，不能证明 Responses、SSE、工具调用和 usage 兼容。新版“Codex 接入”页的探测确实经过 Responses 网关，但它测试的是整个方案，可能自动换源，仍不能替代单个 Provider 的健康验证。应增加指定来源、指定模型的探测，展示实际来源、HTTP 状态、终态、耗时和 usage 是否完整。

### 5.3 方案管理：新版一致性明显更好

旧版以 `is_default` 和 `enabled` 推导当前方案，`findActive()` 从按默认/名称排序的列表中取第一个 enabled 方案；这不是明确且唯一的 active 状态。激活过程也没有事务或唯一约束。

旧版创建 Provider 会自动加入默认方案，虽然减少配置步骤，但也会让“添加配置”隐式改变运行中的候选来源。这种副作用不适合精确控制网关流量的工具。

新版的显式 active 字段、部分唯一索引、事务化激活、空方案校验、成员引用保护和删除约束值得保留。新增来源后由用户决定加入哪套方案，操作含义更稳定。

两版都还缺少版本冲突检测：Web 多标签页、未来桌面与 Web 同时编辑时，后保存的配置可以覆盖先保存的配置。可增加 `version` / `If-Match`，冲突返回 409/412 并要求刷新，不应仅依赖 updated_at。

## 6. 路由、负载均衡与失败转移

### 6.1 按序使用的边界

旧版先根据可用状态、限额、默认模型和并发快照构建候选集，再依次执行；新版先生成成员顺序，在每次准入时读取最新 Provider 配置并原子检查限额。

新版更能防止“规划时允许，真正执行时已超限”的并发问题。但 route 只冻结来源 ID，规划之后若来源被移出方案并删除，准入查找可能抛出 404，提前中断整个请求，而不是跳过并继续。这需要定义明确策略：正在运行的请求使用哪个配置版本；候选消失应如何处理；新的禁用配置是否立即作用于尚未开始的 attempt。

### 6.2 加权算法：两版各有不同问题

旧版虽然把 weight 放进 Candidate，却仅对候选数量取模轮询。权重 10:1 与 1:1 的调度效果基本一样。旧版在构造候选集时先过滤不可用来源是合理的，按 scheme + model 保持游标也比全局一个游标更独立，但并不能掩盖权重未参与计算的问题。

新版用总权重取模并旋转候选列表，所有来源可用时权重有效；已有 2:1 测试通过。但失效来源仍占据权重区间：

```text
配置：A(100), B(1), C(1)，A 已禁用或被额度拒绝
票号 0..99：先选 A，准入失败后落到 B
票号 100 ：先选 B
票号 101 ：先选 C
结果：B 101 次，C 1 次
期望：有效候选 B/C 以 1:1 分配
```

除此之外，新版按连续票号区间分配：100:1 可能先连续选择 A 100 次，再选择 B 一次。这是加权轮询的一种实现，但并不平滑；全局 AtomicLong 也让不同方案之间共享进度。

推荐顺序：先修正有效候选集，再判断是否需要平滑加权轮询。对于本地轻量网关，平滑加权轮询或加权随机都可以，但必须用可重复的测试验证失效、恢复、限额耗尽、成员变化后的公平性。选中后仍必须保留原子准入；不能把预过滤当成最终配额检查。若选中后准入失败，应从剩余可准入候选重新决策，并为候选数/尝试次数设置上限。

### 6.3 失败转移必须有执行语义

旧版对多类 HTTP 错误执行 markFailure 和继续换源，这会把 400 参数错误、401 密钥错误、429 限流和 5xx 上游故障混在一起。尤其 POST 在网络错误或 5xx 时是否已被上游执行，通常不能只根据本地观察确定。

新版默认只在收到 429 且存在后续候选时重试；400/401/5xx 和连接异常不会普遍重放，3xx 重定向被拒绝。这种保守策略适合作为默认值，因为“自动换源”不能保证供应商侧 exactly-once。

值得从旧版吸收的是冷却和错误诊断，而不是统一重试全部错误：

| 情况 | 建议处理 |
|---|---|
| 本地禁用/本地限额/并发满 | 不发请求，记录结构化跳过原因，选下一个 |
| 429 | 按供应商契约允许换源；解析 Retry-After，设置有上限的冷却 |
| 401/403 | 记录配置/授权问题；是否跨源继续必须是显式策略 |
| 400/422 | 默认原样返回，避免掩盖请求不兼容 |
| 5xx/连接中断 | 默认保守；只有明确满足未执行或支持幂等契约时才重试 |
| SSE 已输出部分内容 | 不透明切换来源，记录流中断和已有 usage |

旧版区分 markFailure 与 markStreamFailure，后者保存诊断而不一律触发冷却，这种按失败阶段分类的意识值得保留。

新版当前没有冷却，顺序方案中持续返回 429 的首个来源会被每次请求重复访问；同时 1800 秒是每个 attempt 的 total timeout，并不是整个网关请求共享的总截止时间。应增加 `requestDeadline`、`maxAttempts` 和错误分类，避免多来源请求累计等待过长。

## 7. 限额与 token 统计

### 7.1 旧版值得继承的是规则模型

旧版独立 `provider_limits` 可以为同一来源建立多条规则，表达请求次数、输入 token、输出 token、总 token，以及分钟、小时、天、周、月、自定义秒数窗口。产品方向上比把固定两个限额字段写进 Provider 更有扩展性。

但实现有四个重要限制：

1. 窗口实际是 `now - Duration` 的滚动窗口；DAY 不是自然日，MONTH 是固定 30 天，不是自然月。
2. 查询历史用量后才继续请求，没有原子预留；多个并发请求可以共同越过限额。
3. usage 缺失时请求字节数/4 的估算只补部分输入信息，output/total 可能仍为 null，`SUM(total_tokens)` 无法可靠限制未知消耗。
4. limitType、windowType、unit、onExceeded 的字符串契约缺少完整一致性校验；不识别的值可能退化成零用量或默认窗口，而不是明确拒绝配置。

独立规则表是好经验，字符串开关和检查后直接放行不是应继承的实现。

### 7.2 新版的关键进步：预留与结算

新版在 `BEGIN IMMEDIATE` 内完成：读取 Provider、计算已占用 token/请求数/在途并发、插入 IN_FLIGHT 和预留量。SQLite 单写者机制使这些步骤不会被另一条准入写事务穿插。

结算通过 `WHERE status='IN_FLIGHT'` 保证幂等：有权威 usage 时用真实输入+输出替换预留；没有可信 usage 且不能确认未执行时保留 reservation；明确拒绝且无 usage 时将 token 占用归零。崩溃恢复也释放在途状态并保留不确定性。

来源：[AttemptRepository.admit](/D:/Projects/codex-squire/backend/src/main/java/dev/squire/usage/AttemptRepository.java:43)、[finish](/D:/Projects/codex-squire/backend/src/main/java/dev/squire/usage/AttemptRepository.java:90)。

这比“所有失败都记零 token”或“估算 token 当成实际 token”更可信，后续扩展多窗口时应保留这个基础。

### 7.3 必须说清楚的统计口径

| 项目 | 当前语义 | 需要避免的误解 |
|---|---|---|
| requests | 实际准入的上游 attempt 数；换源会增加次数 | 不等于用户发起的逻辑请求数 |
| input/output | 从上游 usage 得到的数值 | 缺少 usage 时数据库中的 0 不意味着实际没消耗 |
| cached | 输入 token 的子集 | 不能再次加到 input + output 中 |
| chargedTokens | 本地预算占用，可能是真实用量，也可能是保留的 reservation | 不是供应商确认账单，更不是金额 |
| reasoning tokens | 旧版可记录，新版暂缺 | 通常属于 output 的细分，不应再次累加为总量 |
| 日限额 | 新版按 UTC 自然日；旧版 DAY 是过去 24 小时 | 不能在迁移时直接视作同一规则 |
| success/failure | 目前主要按 attempt 状态统计 | 上游已完成但下游取消时需要双维度解释 |

预留机制是本地预算约束，并不保证供应商费用绝不超限。若剩余额度 100、预留 100、上游实际返回 150，结算仍然会超过设定值。要提高保护程度，需要更合理的输入估算、输出上限、供应商侧配额以及明确的误差策略；不能仅靠一个固定 reservation 声称硬性费用上限。

### 7.4 新版需要补齐的限额细节

- 目前只支持两个固定日限额，无法表达“每分钟 60 次，同时每日 100 万 token”。建议恢复独立规则模型，使用枚举和值对象描述指标、窗口、时区和拒绝动作。
- 默认 tokenReservation 为 8192；若用户把日限额设得更小，来源可能永远无法准入。界面应明确显示被哪条规则拒绝以及需要多少剩余额度。
- 实际并发检查覆盖所有日期，但 statistics 仅查询当日；跨 UTC 午夜运行的请求可能仍占用并发，概览却显示当日在途为 0。应将全局在途与当日统计分开。
- admit 中分别读取 LocalDate 和 Instant，在午夜边界存在 day 与 started_at 不一致的微小窗口。应读取一个 Instant，再从同一时间点派生日标识。
- 当前统计随日志增长聚合明细；早期可以保留，但需测量查询成本后再增加汇总表。旧版 usage_buckets 只有结构，没有实际汇总链路，不应直接恢复一个无人维护的表。

建议规则核心为 `QuotaRule(metric, window, zone, threshold, action)`，准入结果为 `AdmissionDecision(admitted, reasons, reservation)`。所有生效规则与预留应在同一个写事务中判断，未知用量不能绕过总 token 限额。

## 8. Responses 转发、SSE 和结束状态

### 8.1 两版流式设计的实际差别

旧版在转发 SSE 时保留有界尾部，再从捕获内容分析事件。相比保存整个响应内存可控，且能捕获通常位于末尾的 usage。缺点是解析依赖尾部保留范围，较大的终态事件或后续数据可能挤掉关键信息；逐行解析也不能完整替代 SSE 事件组装。

新版增量处理字节、行和多行 data 事件，支持 UTF-8 跨块、LF/CRLF，遇到过大事件后可以跳过并继续观察后面的终态。使用 `writeAndFlushWith` 明确逐块刷新，比旧版 `writeWith` 更清楚地表达交互流需求。原始响应字节仍直接转发，usage 观察不修改工具调用内容。

新版对 input/output 检查整数、非负和溢出，并限制 cached 在输入范围内；但 cached 使用 `asLong`，并非所有细分字段都使用同样严格的整数类型校验。也尚未保存 reasoning_tokens。

两版请求体都仍先聚合到内存，旧版默认上限 24 MiB，新版 8 MiB；不能把“响应流式转发”扩大描述为请求/响应所有方向都完全不缓冲。

源码：[旧 StreamCapture](/D:/Projects/deprecated-projects/codex-squire/backend/src/main/java/com/codexmanager/gateway/GatewayService.java:812)、[新 UsageInspector](/D:/Projects/codex-squire/backend/src/main/java/dev/squire/gateway/UsageInspector.java:11)。

### 8.2 最值得迁移的旧版经验：终态与取消竞争

旧版取消处理会查看已经观察到的上游终态：COMPLETED 保持 SUCCESS，FAILED/INCOMPLETE 按失败处理，只有没有终态证据时才归为 CANCELLED。配套测试覆盖终态后 HTTP writer 未结束、Provider 状态更新中取消、最终数据库写入中取消等时序。

新版使用 usingWhen 统一清理是更紧凑的资源管理方式，但 asyncError/asyncCancel 分支直接传入 TRANSPORT_ERROR/CANCELLED，忽略已确认的终态。探针已经证实完成后的 usage 不丢失，但结果分类丢失。

进一步说，直接照搬“completed 后总记成功”也不足以描述真实情况：上游可以执行成功，而客户端并没有收完结果。推荐把状态拆成：

```text
upstreamOutcome: UNKNOWN | COMPLETED | FAILED | INCOMPLETE | REJECTED
deliveryOutcome: PENDING | DELIVERED | CLIENT_CANCELLED | WRITE_FAILED
usageState:      RESERVED | REPORTED | NOT_APPLICABLE
```

上游状态由协议证据决定；交付状态由本地写出生命周期决定；usage 独立结算。这样“已扣 token，但客户端取消”有准确表达，也能分别计算上游成功率与用户成功交付率。

同类问题还有新版 3xx 分支先设置 REDIRECT_REJECTED，再抛出错误后被 usingWhen 的异常清理覆盖成 TRANSPORT_ERROR。安全行为是正确的，但日志分类不精确。应保留明确业务结果，只对尚未确定分类的异常赋默认状态。

来源：[旧 recordCancelledAttempt](/D:/Projects/deprecated-projects/codex-squire/backend/src/main/java/com/codexmanager/gateway/GatewayService.java:524)、[旧 GatewayCancellationTest](/D:/Projects/deprecated-projects/codex-squire/backend/src/test/java/com/codexmanager/gateway/GatewayCancellationTest.java:44)、[新清理分支](/D:/Projects/codex-squire/backend/src/main/java/dev/squire/gateway/GatewayService.java:76)。

### 8.3 JSON、错误和协议范围

旧版 JSON 转发聚合响应后会按成功路径记账，没有像流式终态那样完整区分 HTTP 200 中的 failed/incomplete/空内容。新版通过 UsageInspector 判断语义终态更一致；但两版都需要把 HTTP 状态、协议结果和 usage 三者独立处理。

新版只放行必要的请求/响应头，并拒绝重定向，适合作为凭据代理的默认策略。旧版主要排除静态 hop-by-hop 头后复制更多头，容易把 Set-Cookie 等上游上下文带给本地客户端，也没有完整处理 Connection 指定的动态 hop-by-hop 名称。

新版拒绝 background 和 previous_response_id，是为当前无跨来源会话归属的实现限定边界。旧版记录 session_key 不等于已解决会话亲和性；未来支持 previous_response_id、服务器端存储状态或 compact，需要明确 responseId 到来源的归属及失效策略，不能继续任意跨源轮询。

旧版 `/responses/compact` 调用普通 handle，并不能证明具备 compact 请求/响应语义；新版没有这个接口是诚实的范围限制。两版都应建立可自动验证的兼容能力表，逐项声明普通 Responses、SSE、工具调用、压缩、后台任务、状态续接是否支持。

## 9. 请求日志与可观测性

旧版这部分的需求认识比新版丰富。其 request_logs 包含 session_key、scheme_id、错误码、总耗时、首包耗时、请求/响应字节数、缓存输入、推理输出、usage 来源和 attempt_chain。这些字段直接服务于排障，不是为了扩充表结构。

例如用户说“今天特别慢”，只有总耗时无法区分上游首包慢、生成慢和客户端读得慢；只有失败计数无法区分无可用来源、上游拒绝、协议终态失败和流中断。

新版已有 requestId、Provider 名称快照、HTTP 状态、usageState 和分页，是良好基础，但当前 attempts 返回对象没有把数据库中的 scheme_id 暴露为完整追踪信息，也没有首包、字节量、错误码、上游 request ID 和 attempt 序号。

建议保留“一次逻辑请求对应多次来源尝试”的结构：

- `GatewayRequest`：逻辑 requestId、模型、入口时间、最终交付结果、总耗时、方案版本。
- `ProviderAttempt`：attemptId、序号、Provider 快照、HTTP 状态、上游 requestId、首包耗时、失败阶段、协议结果。
- `UsageSettlement`：计量来源、真实值、预留值、最终预算占用、结算时间；早期可仍作为 attempt 字段，不必立即单独建表。

两版对“没有进入任何来源”的请求都缺少完整请求级日志，例如无活动方案、全部禁用/超限。新版应至少返回并保存可读的路由拒绝原因；鉴权失败可采用独立安全事件/聚合计数，避免恶意请求无限膨胀业务日志。

保留旧版幂等终态写入和启动恢复经验，但不要恢复未经控制的请求正文采集。当前目标是 Provider 管理，默认记录协议元数据即可；将来增加会话/提示词管理时，也不意味着所有经过网关的提示词应自动持久化。

旧版 `UsageRepository.inflight()` 直接返回 0，不能当成真实在途统计。旧版 UI 把数据库累计统计描述成“网关启动以来”，也会导致用户误解口径。新版应继续避免“来源已启用 = 在线”“HTTP 200 = 完整成功”等表述。

来源：[旧 RequestLogRepository](/D:/Projects/deprecated-projects/codex-squire/backend/src/main/java/com/codexmanager/requestlog/RequestLogRepository.java:19)、[新 AttemptRepository](/D:/Projects/codex-squire/backend/src/main/java/dev/squire/usage/AttemptRepository.java:30)。

## 10. SQLite、事务与数据演进

### 10.1 不要混淆工具选择与使用正确性

旧版选择 JdbcTemplate、Hikari、Flyway 本身合理；问题是没有把配置和事务落实到运行时。探针用实际 DatabaseConfig 和 Spring 数据源/Flyway 自动配置，得到以下有效值：

```text
poolSize=10
connectionTimeout=30000
connectionInitSql=null
foreignKeys=0
journalMode=delete
```

这与 YAML 声明的 pool=4、timeout=5000、PRAGMA foreign_keys=ON 不一致，原因是自定义 DataSource Bean 缺少对应 Hikari 属性绑定。仅审阅 application.yml 会得出错误结论。

新版在每次创建连接时设置 foreign_keys 和 busy_timeout，启动时启用 WAL；配置修改、准入与结算均有清晰事务边界。这些行为比“用了连接池”更重要。

但不应因此认定去掉 JdbcTemplate 就是长期最佳方向。新版每次操作都创建/关闭 SQLite 连接，自定义 SQL 映射与异常翻译也形成维护责任。是否引入小型连接池应由压测结果决定；可以采用 JdbcTemplate 简化重复 JDBC，同时保留正确的 SQLite 连接初始化与 BEGIN IMMEDIATE 事务语义。迁移到 Spring 事务抽象时必须实际验证写锁获取时机，不能因为加了 `@Transactional` 就假设等价。

### 10.2 旧版 Flyway 是优先级很高的正面经验

旧版 V2 修复了“空流被记为成功”的历史数据；V3 归一化了来源冷却状态。这说明迁移不仅用于建表，还用于已发布版本的问题修复。

新版当前通过 `PRAGMA user_version` 区分 0/1，再用 `schema.split(";")` 执行 V1。对于当前简单初始化 SQL 可用，但它没有一组可扩展、可校验的迁移历史；分号拆分也不适合复杂 SQL 字面量、触发器等语法。未来新增规则表或日志状态时，应先引入正式迁移机制，而不是继续在启动函数里堆条件。

建议引入与当前依赖版本兼容、经过 SQLite 实测的 Flyway 方案，或同等成熟迁移工具。针对现存新版 V1 数据库设计明确接管路径，检查结构再建立迁移基线；不要对任意非空数据库盲目 baseline。

必须验证：全新安装、V1 升级、重复启动、迁移中途失败、迁移校验失败、旧二进制误开新数据库、备份恢复。WAL 模式备份应采用 SQLite 一致性备份方式，或停机并正确处理 WAL，不能认为运行时只复制主 db 文件一定完整。

### 10.3 实例锁与崩溃恢复

两版都会恢复上次未完成的请求；旧版没有数据目录实例锁，第二个进程启动时可能将第一个进程的在途请求误判为中断。新版先取得 instance.lock 再初始化/恢复，方向正确。

后续应把实例锁扩展为完整的运行发现协议：进程身份、端口、数据目录、版本和就绪状态。启动第二个入口时连接已有实例或明确报错，不应盲目启动第二份后端。

### 10.4 可维护性与规模边界

新版 ProviderRepository 和 scheme_members 插入省略列名，依赖表字段顺序；应改为显式列清单，避免加字段后引入难排查错误。两版方案列表都有多次逐方案查询，少量配置下可接受，但不能把这种模式无条件扩展到大量会话/提示词数据。

专用数据库线程池最多 4 个线程不等于 SQLite 可以同时完成 4 个写事务。写者依然串行；一旦数据库忙等待占满工作线程，管理读取和结算也会排队。需要测量准入、结算、列表的延迟，设置可解释的忙碌错误，并避免把 DB 异常映射成“上游连接失败”。新版 GatewayService 的广泛 onErrorMap 当前存在这种误分类。

## 11. 安全、凭据与恢复

### 11.1 新版应作为统一安全基线

新版默认 loopback，使用独立随机管理令牌、网关令牌和 32 字节主密钥；采用 AES-GCM、恒定时间令牌比较、精确 Origin 校验、本地文件 ACL、拒绝上游重定向和有限头部转发。

这些措施解决的是具体威胁：阻止无凭据访问管理面、避免网关 key 自动拥有管理权、降低远程误暴露风险、防止重定向携带上游密钥到其他地址。它们比只配置 CORS 更完整。

旧版则有默认公网卡监听、管理 key 未接入、主密钥固定默认值三个叠加问题。未来封装桌面时也不能退回“本地程序不需要鉴权”的假设。

### 11.2 从旧版吸收格式设计，不吸收默认密钥

旧版 ciphertext 有 `v1:` 前缀和密钥指纹；新版密文只有 nonce:ciphertext，缺少格式版本和 keyId。建议增加版本化封装，例如持久化算法版本、keyId 和密文格式，为主密钥轮换保留迁移路径。

旧版 rotate 方法不生成新 key，只重新读取旧值，说明安全操作必须以实际效果验收。未来轮换应验证：旧 token 是否失效、新 token 是否生效、配置中的凭据是否可更新、失败能否恢复，必要时提供明确的短期兼容窗口。

### 11.3 新版仍缺少密钥丢失的可靠处理

`LocalSecrets.readOrCreate` 在文件缺失时生成新密钥。如果已有数据库仍包含使用旧主密钥加密的 Provider key，而 master.key 丢失，启动会创建新主密钥，直到解密时才失败。应识别“已有加密数据但缺少主密钥”的异常状态并启动失败，提供恢复说明；不能把它当作全新安装自动修复。

同时保存于同一用户目录的 DB 和 master.key，不是操作系统凭据库或硬件密钥隔离；它主要防止单独数据库文件泄露。桌面阶段可以引入 OS 凭据存储，但应先建立明确的备份、导出、轮换和恢复模型。

源码：[LocalSecrets](/D:/Projects/codex-squire/backend/src/main/java/dev/squire/security/LocalSecrets.java:33)、[AccessFilter](/D:/Projects/codex-squire/backend/src/main/java/dev/squire/security/AccessFilter.java:22)。

## 12. 前端交互、API 契约与双端适配

### 12.1 旧版的问题不只是组件文件大

旧版 App.tsx 以很长的单行代码聚合多个功能，阅读和修改成本较高。但更重要的是交互与 API 契约缺口：

- 统一请求函数仅对 204 跳过 JSON 解析；后端一些 delete/create limit 返回空 `Mono<Void>` 且未声明 204，成功后仍可能因解析空响应而向用户报错。
- 多处 mutation 缺少明确异常展示；用户难以判断保存失败还是刷新失败。
- 方案页面虽有创建、选择、模式修改和激活，但没有形成完整成员增删、排序、覆盖参数和删除方案交互。
- 选择方案的 effect 会影响尚未提交的模式编辑状态，草稿与服务端状态边界不够清楚。
- 部分 PanelHeader 按钮没有 onClick，页面上看得到动作却不能完成操作。
- 页面中的“就绪/在线”标签及统计口径缺少真实运行证据。

这说明前端验收必须围绕用户任务，而不是“页面有几个按钮”。一项能力至少要验证创建、修改、保存失败、取消、刷新后保持、空状态和引用冲突。

源码：[旧 api.ts](/D:/Projects/deprecated-projects/codex-squire/frontend/src/api.ts:3)、[旧 LimitController](/D:/Projects/deprecated-projects/codex-squire/backend/src/main/java/com/codexmanager/limit/LimitController.java:17)、[旧 App.tsx](/D:/Projects/deprecated-projects/codex-squire/frontend/src/App.tsx:41)。

### 12.2 新版改善与剩余问题

新版按 Providers/Schemes/Requests/Connection/Overview 分拆，使用类型化 ApiClient、登录态、明确 CRUD 状态、Dialog、分页、复制反馈、导出和响应式布局，当前任务闭环更完整。

但还需要改善：

- 全局每 5 秒刷新配置和统计，旧请求没有 AbortController/序号校验，慢响应可能覆盖后来的结果；退出重新登录也存在旧 refresh 返回影响新状态的可能。
- 配置数据、实时统计和编辑草稿应分别管理，避免为了刷新统计重复拉取所有配置。
- ApiError 只保存 status/message，后端的稳定错误 code 没有完整保留；需要本地化与结构化错误，而不是直接展示各种英文异常。
- 管理 token 放在 sessionStorage 是当前 Web 方案，桌面不应简单照搬长期凭据存储。
- Connection 页面直接 `fetch('/v1/responses')`，绕过 ApiClient。因此“有统一 API 类”并不意味着未来 Tauri 的全部网络调用已经可替换。

不必为了修这些问题立刻引入大型状态框架。先把请求取消、过期响应丢弃、刷新去重和错误码契约做正确，再根据页面复杂度决定是否引入 TanStack Query 等服务端状态工具。

源码：[新 App.tsx](/D:/Projects/codex-squire/frontend/src/App.tsx:30)、[Connection.probe](/D:/Projects/codex-squire/frontend/src/features/Connection.tsx:20)。

### 12.3 后续 Tauri 的真正边界

当前 Web 开发环境通过 Vite 代理 `/api` 和 `/v1`，而 Tauri 打包后的静态页面没有 Vite 代理。因此桌面工作不是把 React dist 放进壳里就完成。

建议引入一个范围有限的 `BackendConnection` / transport 配置：包含后端地址、认证获取方式、请求超时和平台发现机制。管理调用与 Responses 探测都使用这层；Web 模式可以继续相对路径代理，桌面模式通过宿主发现实际 loopback 地址和凭据。

桌面 Origin/CORS/CSP、WebView 网络限制、安装目录资源定位、后端就绪和退出归属也必须纳入验收。不能为省事允许任意 Origin 或把管理密钥直接硬编码进前端资源。

## 13. 启动脚本与旧版桌面骨架

### 13.1 可继承的启动经验

旧版提供 Windows `.cmd` 包装、工具链准备和本地依赖兜底，照顾了双击启动与网络受限环境。这些可以作为交付体验参考，但下载兜底应补充来源固定和校验和，不能因为方便就让构建输入不可追溯。

新版两个独立脚本符合当前明确需求，使用指定 JDK 17、检查主版本、支持数据目录/端口/后端 URL、离线构建与 lockfile，并在端口占用时递增寻找空端口。

剩余闭环缺口：自动调整后端端口后，另一个 Web 脚本需要使用输出的 BackendUrl；Web 端口到 5175 及以上时，默认后端 Origin 列表不再匹配，只给出警告；脚本输出 URL 也不表示服务已经 ready。JAR 文件名包含固定版本号，升级版本后脚本容易失配。

建议增加受限权限的运行描述文件和就绪检查，保留两个独立启动入口，但让它们能够发现同一后端。端口探测与实际绑定之间仍有竞争，最终应以实际绑定结果为准。

### 13.2 旧版桌面只能作为问题样本，不宜直接复用

静态审阅发现：

- `start-desktop.ps1` 先启动后端，Rust setup 又尝试启动后端，存在两个生命周期所有者。
- Rust 使用 `Builder.run(|app,event| ...)` 形态，与 Tauri 2 中 Builder 的运行入口不匹配；通常需要先 build context，再由 App.run 接收事件回调。此项未运行 cargo 验证。
- 后端 JAR 资源名、打包路径与开发启动路径缺少统一协议，仍依赖开发机 JDK 默认路径。
- `spawn().ok()` 吞掉启动错误，stdout/stderr 丢弃，没有后端就绪检测和失败回显。
- 退出时杀包装进程不保证 Java 子进程也退出；反过来，连接了用户独立启动的后端时也不应随桌面退出将其杀掉。
- React 相对 `/api` 在 packaged origin 下没有自动对应后端，当前 CORS 配置也没有完整覆盖桌面来源。

来源：[旧 Rust main](/D:/Projects/deprecated-projects/codex-squire/desktop/src-tauri/src/main.rs:25)、[旧 start-desktop.ps1](/D:/Projects/deprecated-projects/codex-squire/scripts/start-desktop.ps1:7)。

未来应由一个 RuntimeSupervisor 决定“启动自有后端”还是“连接已有后端”，记录 ownership，并管理就绪、日志、优雅退出、异常回收。等 Web/后端协议稳定后再实现桌面，是当前合理的开发顺序。

## 14. OOP 与 Alibaba P3C 视角的评价

### 14.1 当前代码离“高标准”还有哪些距离

旧版有显式 Service，但 GatewayService 同时承担路由尝试、HTTP、解析、健康状态和日志，方法参数较多，生命周期状态散布在多个分支。存在 Service 并不自动满足单一职责原则。

新版降低了网关编排复杂度，但 ProviderRepository 同时做输入处理、密钥加密、业务校验、ID 生成和持久化；SchemeRepository 也承担激活和空方案等规则；AttemptRepository 同时负责准入决策、配额计算、结算、查询统计。随着规则增加，Repository 很容易再次变成大型业务类。

推荐按变化原因拆分职责，而不是机械地为每个类创建接口或追求层数：

| 原则 | 当前问题 | 建议 |
|---|---|---|
| 单一职责 | 旧 GatewayService 过重；新 Repository 承载多类业务策略 | 拆出有实际独立策略的准入、终态判定、凭据管理 |
| 开闭原则 | 新限额固定字段；路由策略 if 分支随能力增加 | 在第二种窗口/策略真正落地时引入对应策略接口 |
| 依赖倒置 | GatewayService 构造内部 WebClient，测试替换成本较高 | 注入配置好的上游客户端或小型 transport 接口 |
| 领域不变量 | 旧版事务缺失；新逻辑分散于 DTO/Repository/SQL | 明确每个不变量的负责层，数据库再提供约束兜底 |
| 类型安全 | 限额动作、日志终态等字符串散落 | 使用 enum/value object，持久化序列化保持明确 |
| 异常语义 | 新版 DB/解密错误可能转成上游失败 | 区分配置错误、存储错误、协议错误、上游错误 |
| SQL 可维护性 | 新版 INSERT 部分省略列名 | 明确字段清单，避免表顺序耦合 |
| 并发正确性 | 新终态分类、刷新覆盖；旧配额/方案竞态 | 用时序和不变量测试保障，不以锁数量评价质量 |

新版 Checkstyle 当前主要覆盖命名、花括号、单行语句、禁止星号 import 等基础规则，不能据此宣称完整 P3C 合规。建议将规范落为可执行项目约定：业务命名、异常码、校验边界、事务边界、集合不可变性、线程与资源释放、日志脱敏、SQL 列清单、模块依赖以及测试要求。

工具上可以评估与 JDK 17 兼容的 P3C/PMD 规则、SpotBugs 或少量 ArchUnit 约束；应选择能发现实际缺陷的规则。不要为满足形式规范给 record 添加重复 getter，也不要为简单数据对象设计无用继承体系。

### 14.2 推荐的演进结构

保持单体、按领域分包，不需要立即拆 Gradle 多模块、引入微服务或完整 DDD 框架。目标职责可以是：

```text
api
  ProviderController / SchemeController / UsageController / GatewayController
provider
  ProviderService -> ProviderRepository
  CredentialService -> LocalSecretStore
scheme
  SchemeService -> SchemeRepository
gateway
  GatewayService
  RoutingPolicy (Ordered / Balanced)
  AdmissionService -> QuotaPolicy + AttemptRepository
  UpstreamClient
  UsageInspector + OutcomeResolver
usage
  UsageQueryService / AttemptRepository
persistence
  SQLite connection configuration / transaction boundary / migrations
runtime
  InstanceLock / RuntimeDescriptor
```

这是职责目标，不要求一次创建所有类。最先值得抽出的对象是 OutcomeResolver 和 AdmissionService，因为已经有实际复杂性和测试需求；只有当双端、密钥轮换或更多路由策略落地时，再扩大对应抽象。

## 15. 应从旧版吸取什么，如何吸取

| 旧版经验 | 是否继承 | 新版落实方式 | 必须避免 |
|---|---|---|---|
| 独立、多条限额规则 | 是，高优先级 | 规则表 + 强类型窗口/指标 + 原子预留 | 原样搬 LimitEvaluator 的先查后放行 |
| Flyway 与历史数据修复 | 是，高优先级 | 先接管新版 V1，再建立版本化迁移测试 | 盲目 baseline、启动时随意改表 |
| 完成后取消、写库中取消测试 | 是，高优先级 | 迁移测试意图，使用新 usingWhen 链路验证 | 只复制 mock，而没验证实际状态与结算 |
| 首包、错误码、字节数、reasoning 等诊断 | 是 | 丰富 attempt，增加请求级关联 | 无目的存储完整提示词/响应正文 |
| 冷却及 stream failure 分类 | 是，调整策略 | 按错误分类处理，支持 Retry-After | 对 400/401/所有异常统一冷却重试 |
| 方案成员启用、描述、覆盖参数 | 按需求 | 事务更新，完整 UI 与校验 | 先加字段后补行为 |
| key 指纹、密文格式版本 | 是 | 非明文识别 + version/keyId + 真轮换 | 固定默认主密钥、无效果的 rotate |
| 显式 Service 领域边界 | 有选择继承 | 为真正业务操作建立应用层 | 纯透传 Service 和无意义接口层 |
| Windows 启动兜底 | 有选择继承 | CMD 包装、工具检测、受控依赖校验 | 静默下载、吞错、重复启动后端 |
| Tauri 目录结构和开发尝试 | 作为参考 | 重新设计生命周期与 transport | 直接复用当前 Rust 启动代码 |
| 伪模型列表、普通转发冒充 compact | 否 | 协议能力明确标注、逐项兼容测试 | 用路径存在证明协议支持 |
| 请求字节/4 估算 | 仅作低可信参考 | 与真实 usage 分栏，不能代替账单 | 把估算或缺失值当成确定 token |

最应继承的是旧版已经暴露出来的真实业务问题，以及对这些问题的测试和建模，而不是它现有的代码组织方式。

## 16. 实施优先级与验收标准

以下是后续工作建议，本次没有实施应用修复。对新版的整改应先解决语义正确性和数据升级，再扩展功能。

### 阶段 A：正确性基线

| 工作项 | 验收标准 |
|---|---|
| 修复有效候选加权 | A100 不可用、B1/C1 可用时分配恢复等权；恢复/禁用/限额耗尽不导致长期偏斜；不同方案互不污染游标 |
| 明确终态与交付结果 | completed 后取消保留上游成功和 29 token，同时记录客户端取消；failed/incomplete 不被覆盖成一般传输错误 |
| 保留已确定错误分类 | 3xx 记录 REDIRECT_REJECTED；DB/密钥失败不标记为 UPSTREAM_FAILURE |
| 迁移机制 | 现有新版 V1 可以升级，旧数据保留；重复启动无副作用；迁移失败能识别并恢复 |
| 主密钥缺失检测 | 有加密数据但缺 master.key 时明确失败，不创建替代主密钥掩盖问题 |
| 增补生命周期测试 | 正常、异常、取消、取消发生在准入/写库/终态后，均至多结算一次且最终释放并发 |

旧版 P0 安全和事务问题应作为“不允许继承”的门槛；如果旧版仍继续提供实际服务，需要单独安排修复，不能因新版存在而忽略旧服务的运行风险。

### 阶段 B：形成可靠的 Provider 管理产品

| 工作项 | 验收标准 |
|---|---|
| 多窗口限额 | 同一来源分钟请求上限与日 token 上限同时生效；并发准入不突破预留约束；滚动/自然窗口明确 |
| 运行状态与冷却 | 展示失败原因和恢复时间；429 尊重 Retry-After；客户端错误不会伪装成来源整体宕机 |
| 请求诊断 | 可按一个 requestId 查看完整尝试链、跳过原因、首包耗时、usage 可信度和终态 |
| 统计口径 | 逻辑请求/attempt 分开；全局在途/当日统计分开；未知用量不混作实际为零 |
| 单来源探测 | 不经过其他来源兜底；验证真实 Responses 及终态，记录可能消耗的用量 |
| 多标签配置冲突 | 编辑使用版本校验；冲突不会静默覆盖，草稿不会被轮询重置 |
| 运行发现 | 两个独立脚本可发现实际端口；端口变化、后端未就绪、错误 Origin 有明确诊断 |

### 阶段 C：桌面与综合工具扩展

先建立统一 transport、实例 ownership、打包运行时和凭据获取方式，再加入 Tauri。验证无开发环境机器安装、端口冲突、重复启动、后端崩溃、桌面退出、连接外部已启动后端、升级迁移及卸载数据策略。

会话管理和提示词管理作为新领域逐步加入，不塞进 GatewayService。会话管理首先定义数据来源、会话身份与本地文件/远端状态边界；提示词管理首先定义模板、版本和应用方式。当前 requestId/session_key 只是相关线索，不是这些领域的现成模型。

## 17. 新旧数据迁移建议

两版 schema 并不兼容：旧版有 8 张业务表，新版有 4 张业务表，Flyway 历史表另计。不要让新版直接指向旧版 DB，也不要用 SQL 重命名表冒充迁移。

| 旧数据 | 新版目标 | 迁移规则 |
|---|---|---|
| providers.id | providers.id | UUID 字符串原则上可保留；先校验格式和冲突 |
| name | 唯一 name | 旧版不唯一，新版唯一；重复名称必须列出映射，不静默覆盖 |
| base_url + responses_path | base_url | 验证组合端点是否能表示为新 BaseURL；自定义非标准路径标为待处理 |
| provider_secrets | encrypted_key | 使用旧主密钥解密，再用新主密钥加密；不导出明文、不复制密文冒充兼容 |
| priority / weight | scheme_members.position / weight | 以实际 binding 为准；旧字段不一定参与调度，权重范围需校验 |
| max_concurrency=0 | 新版正整数并发限制 | 旧版 0 表示不限，不能直接填入新表；应显式转换策略 |
| default_model | 暂无等价字段 | 保存为迁移元数据或新模型规则，不能丢弃后声称完全等价 |
| enabled/status/cooldown | enabled + 未来 runtime state | 静态启用与临时故障分开，不把短暂冷却永久迁成禁用 |
| routing_schemes | schemes | 保留方案名和成员；旧 active 语义不唯一时列出待确认候选 |
| member enabled/override | 未来成员字段或迁移报告 | 新版暂无等价项，不得静默丢失约束 |
| provider_limits | 未来 quota_rules | 必须保留窗口语义；过去 24 小时不能无提示改成 UTC 自然日 |
| request_logs | 历史记录/attempts 兼容层 | 保留原 usageSource、estimated、null 与状态，不把历史未知值当实际 0 |
| app_settings 网关 key | 独立 gateway.token | 默认生成新的访问身份，旧 Codex 配置需要明确更新 |
| usage_buckets | 不直接作为可信账本 | 当前无完整维护链路，应以核实后的明细为依据 |

建议迁移流程：冻结旧版写入并做一致性备份，离线读取到中间模型，生成仅含非敏感信息的迁移预览，列出所有不可等价映射，在独立新目录事务导入，核对数量/引用/统计，最后切换 Codex 接入配置。失败时仍能回到原目录，不在原库上进行不可逆试验。

特别注意旧版外键可能实际关闭，迁移前应检测孤立 provider_secrets、无效成员和孤立限额，不能只相信 CREATE TABLE 的外键声明。历史日志也不能直接导入当日配额而不定义口径，否则切换后可能立即超限或错误重置额度。

## 18. 建议保留的回归测试清单

现有 14/22 项测试是证据，但测试数目本身不能代表覆盖完整。新版最应补的是业务不变量和并发时序，而非逐行对应实现的测试。

| 维度 | 优先测试 |
|---|---|
| 路由 | 顺序命中后不重复；权重生效；不可用权重移除；成员变化；方案隔离；来源删除竞争 |
| 准入 | 最后一个额度并发争用；多个规则同时约束；动态并发配置；取消发生在资源交付前 |
| 结算 | 真实/未知/拒绝用量；重复完成幂等；进程中断恢复；终态后取消；DB 写入期间取消 |
| SSE | 任意 UTF-8 分片、多行 data、LF/CRLF、巨大事件后仍收到终态、空流、失败终态、缺 usage |
| HTTP | 3xx 无凭据重定向；400/5xx 不意外重放；429 尝试上限；端到端 deadline；头部白名单 |
| 数据 | 方案失败全回滚；外键实际开启；迁移升级；实例锁；午夜统计；主密钥缺失 |
| UI | 全部 CRUD 成败；204 响应；草稿不被刷新覆盖；退出后的过期请求；冲突和禁用状态 |
| 桌面阶段 | 独立/共享后端 ownership、资源路径、WebView Origin、无 JDK 环境、升级与退出 |

本次探针输出汇总：

```text
旧版：poolSize=10, connectionTimeout=30000, connectionInitSql=null
      foreignKeys=0, journalMode=delete
      rotationChanged=false
      failedUpdateMembers=1->0
      failedUpdateName=changed-before-failure
      secondPermitAfterIncrease=false

新版：completedThenCancelStatus=CANCELLED
      completedThenCancelTokens=29
      unavailableHeavyProviderDistribution={b=101, c=1}
```

这些结果说明最有价值的下一步是把已经复现的问题转化为回归测试，再修复实现；在这一基础上恢复旧版好的模型和诊断能力。当前新版的原子准入、安全边界和前端闭环应继续保留，旧版的迁移历史和异常时序经验应成为后续开发的输入。
