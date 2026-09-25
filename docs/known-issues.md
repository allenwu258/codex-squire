# 已知问题与开发顺序

更新日期：2026-09-25。以下只列出仍未完成或需要继续加强的内容。

## 优先解决的正确性问题

| 编号 | 当前行为与影响 | 计划及验收方向 |
| --- | --- | --- |
| K01 | 已覆盖上游终态先到、客户端随后取消的基本场景；最终写库期间取消、真实连接断开与进程退出等竞态仍依赖 Reactor 生命周期 | 补充真实客户端断开、结算失败和进程退出测试，确保交付结果和上游结果都可追溯 |
| K02 | 额度规则已有分钟/小时/日/周/月和基本动作，但 `DEGRADE` 还没有模型级降级策略，输入/输出在途预留采用保守计数 | 定义降级模型映射、规则解释和额度窗口的完整 UI |
| K03 | Provider 运行态已有失败退避、Retry-After 和手动清除冷却；尚未按模型维护能力，也没有后台自动探测调度 | 增加模型维度 runtime、探测历史和可控的后台探测策略 |
| K04 | 配置版本冲突已保护 Provider/Scheme 更新；删除、激活和额度规则更新仍没有统一的版本条件 | 扩展 `If-Match` 到全部可变管理资源并增加审计记录 |
| K05 | 新密文带版本/keyId，但未实现密钥轮换、系统凭据库和跨版本恢复工具 | 桌面阶段接入 Windows Credential Manager/macOS Keychain，并提供轮换和备份验证 |

证据入口：[RoutingService](../backend/src/main/java/dev/squire/gateway/RoutingService.java)、[GatewayService](../backend/src/main/java/dev/squire/gateway/GatewayService.java)、[Database](../backend/src/main/java/dev/squire/persistence/Database.java)、[LocalSecrets](../backend/src/main/java/dev/squire/security/LocalSecrets.java)。已有路由测试覆盖不可用成员过滤和准入失败后的公平性；取消测试覆盖终态后取消，但不代表所有生命周期竞态都已消失。

## 后续能力与边界

- 额度规则覆盖固定窗口和并发准入，但尚未支持成本指标、滚动自定义窗口和完整的供应商账单对账；预留预算不能保证供应商硬费用上限。
- 统计已区分逻辑 requests、attempts 与跨日期 active attempts；跳过候选目前只在最终错误和 attempt 诊断中体现，没有独立 admission 事件表。
- 触发换源的 429 响应体被释放，未解析其中可能存在的 usage；当前总 deadline 是单次 attempt 配置，多个候选没有共享 retry budget。
- 前端轮询在上一轮完成后再等待 5 秒，并给每次管理请求保留 15 秒超时；统一 transport、离线缓存和多窗口实时协作仍未实现。
- Provider 探测保留暂时性错误前的已确认能力，但尚无按模型的探测历史；未探测过的模型在临时失败后保持未知。
- 前端管理调用和连通测试都使用 ApiClient，但 ApiClient 内部仍绑定 Web `fetch`；Tauri 阶段必须抽象 transport、进程归属、Origin 和凭据获取。
- 不支持远程状态续接、compact、WebSocket、OAuth、会话管理和提示词管理；现有元数据不能视作这些能力已经实现。

## 开发顺序

1. 补齐取消竞态、迁移升级和密钥轮换测试，明确 `DEGRADE` 与 admission 事件语义。
2. 增加模型能力注册、探测历史、共享 retry budget 和成本/账单维度。
3. 将版本条件扩展到全部可变管理资源，统一 Web/Tauri transport 和错误本地化。
4. 在明确来源归属后加入会话/提示词领域，再封装 Tauri；桌面生命周期和系统密钥库单独验收。

本文件是当前问题和优先级入口。修复后更新行为说明、回归测试入口和完成提交，不将尚未实现的设计写成现有能力。
