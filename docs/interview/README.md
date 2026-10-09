# CampusHub：事实限定的项目讲述

本项目从黑马点评教学工程迁移，重构由 AI 辅助完成。提交记录、测试数量和文档存在都不证明本人独立实现或已掌握。下列表述仅在本人能解释对应调用链、复现验证并回答追问后使用；未掌握的部分应说明正在接管学习，不直接粘贴成个人经历。

## 可核对的工程主题

| 主题 | 当前实现及取舍 | 证据与追问 |
| --- | --- | --- |
| 角色与资源权限 | USER/MERCHANT/ADMIN 角色校验与资源归属分离；请求模型限制可写字段，不只靠前端隐藏入口 | [权限阶段](../refactor/08-phase-2c-authorization.md)、[API 模型](../refactor/09-phase-2d-api-models.md)。为什么有角色仍需校验资源归属？ |
| 限量活动受理 | Redis Lua 处理资格、时间、库存及重复受理；接口受理不等于数据库已建单。数据库唯一约束与事务兜底 | [准入阶段](../refactor/10-phase-3a-flash-sale-admission.md)。Redis 成功而 DB 暂时失败如何收敛？ |
| Stream 恢复 | 唯一消费者名、pending 接管、有界重试和失败归档；handler、ACK 确认与新订单不是同一个计数 | [可靠消费](../refactor/11-phase-3b-reliable-order-consumption.md)、[诊断语义](../learning/business-diagnostics.md)。何时不 ACK？归档和补偿有哪些不可确认状态？ |
| 缓存一致性 | 门店详情采用 Cache Aside、空值缓存、TTL 抖动、有界 token 互斥和 epoch 发布栅栏；门店写入同事务 outbox 后失效重试 | [缓存治理](../refactor/13-phase-4a-shop-cache-governance.md)。旧查询回填与新写入失效如何交错？TTL 能替代 outbox 吗？ |
| 取消补偿 | 取消已落库未支付订单，DB 状态与 outbox 原子提交；Redis 补偿和 DB CAS 确认分别处理，重试不能重复释放库存 | [订单生命周期](../refactor/12-phase-3c-order-lifecycle.md)。Redis 已成功、DB 确认失败时如何避免假完成？ |
| 搜索选型 | 固定排序、受控分页、名称子串与组合筛选，单请求快照分页；直接读取 MySQL，当前不引入 ES 同步链 | [搜索基线](../refactor/14-phase-4b-shop-search.md)。什么数据量和相关性需求才值得引入 ES？ |
| 安全可观测性 | HTTP/JVM/连接池与 33 条固定 outcome 业务计数；只读内存 scrape，独立 loopback 管理面，避免用户/订单 ID 成为标签 | [可观测性](../refactor/16-phase-6a-observability.md)、[业务诊断](../refactor/17-phase-6b-business-diagnostics.md)。进程计数重启清零意味着什么？为什么不能当唯一订单成功率？ |

源码入口位于 `src/main/java/io/github/frewily/campushub/`：`security/ResourceAuthorizationService.java`、`service/FlashSaleAdmissionService.java`、`service/OrderStreamConsumer.java`、`utils/CacheClient.java`、`service/ShopCacheInvalidationService.java`、`service/OrderCancellationReconciler.java`、`service/ShopSearchService.java`、`config/BusinessMetricsConfiguration.java`。

## 验证层次不能互换

- 默认测试：mock 或纯逻辑验证，不证明实际依赖行为。
- 显式隔离 IT：测试自己启动的本机 MySQL/Redis，目标版本与 Docker 部署验收不相同。
- 目标版本合成 HTTP/故障验收：Phase 6B 的 8 组测试覆盖合成登录/权限、Feed、缓存、订单/outbox 和实际指标；只读取测试自己的验证码，不是接入短信供应商。
- 只读性能基线：见 [性能报告](../performance/README.md)，必须带环境、数据量、并发模型、持续时间、重复次数和原始结果。不能拿两个不同接口的数字作缓存收益对照。

Phase 6B 使用合成数据库 trigger、错误类型 Redis key、加速合成租约到期及应用/collector 优雅重启；尚无 SIGKILL 关键窗口、多副本、任意网络分区、HA、历史数据迁移或生产性能结论。性能阶段也不补足这些可靠性边界。

## 简历表述建议

在真实参与和理解得到确认后，可以概括为：“基于教学工程，在 AI 辅助下按阶段重构校园商户与限量活动后端；围绕 Lua 受理、数据库唯一约束、Stream 重试及事务 outbox 建立失败收敛路径，补充隔离验收和固定标签诊断指标。”

性能条目只引用性能报告中的本机条件与逐轮可追溯数字；不要写“生产支持万人并发”“提升 X%”“零故障”“独立从零开发”，也不要把 VUs 当成用户数量。没有同环境同用例的前后对照，就没有优化提升比例。
