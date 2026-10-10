# CampusHub 面试追问与验证路线

这不是可直接背诵的标准答案。先脱稿回答，再核对源码、测试与边界；能读到答案不等于已掌握。实现基线为 `4882a20`，原始目标的缺口见 [交付盘点](../refactor/19-phase-6d-delivery.md)。

## 1. 来源、业务与架构

1. 哪些来自教学工程，哪些是后续重构？如何用提交、调用链和验证说明，且不把 AI 辅助结果说成独立实现？
2. Merchant、Store、登录账号分别是什么？MERCHANT 角色为什么不足以证明门店归属？
3. 为什么仍保留 Shop/Blog/Voucher 和旧表名？当前模块化分包、评论、后台、通知完成了吗？
4. 为什么采用单体？现在引入微服务、专业 MQ、ES 会多出哪些故障窗口和维护成本？

核对：[项目故事](project-story.md)、[领域模型（设计快照）](../domain-model.md)、[权限阶段](../refactor/08-phase-2c-authorization.md)、[交付盘点](../refactor/19-phase-6d-delivery.md)。必须能区分目标设计、已实现代码与产品能力。

## 2. 认证、安全与 API 边界

1. 当前是否 JWT？opaque token 的存储、滑动过期、登出和禁用账号如何工作？存在独立 refresh token 吗？
2. 数据库角色发生变化后，请求如何得到新权限？读到权限后又发生变化，是否有严格即时撤销保证？
3. ThreadLocal 和 SecurityContext 谁设置、谁清理？异常返回也能清理吗？
4. 验证码冷却、失败预算、原子消费各解决什么？真实短信发出去了吗？
5. 门店创建/修改、活动创建如何限制可写字段和资源归属？所有遗留接口都完成 DTO 隔离了吗？
6. 请求编号是否信任客户端输入？摘要日志能否等同于最终响应耗时或分布式 trace？

源码入口：[认证过滤器](../../src/main/java/io/github/frewily/campushub/security/RedisTokenAuthenticationFilter.java)、[资源授权](../../src/main/java/io/github/frewily/campushub/security/ResourceAuthorizationService.java)、[用户服务](../../src/main/java/io/github/frewily/campushub/service/impl/UserServiceImpl.java)、[请求摘要](../../src/main/java/io/github/frewily/campushub/observability/RequestTraceFilter.java)。回归入口：`RedisTokenAuthenticationFilterTest`、`ResourceAuthorizationServiceTest`、`UserServiceImplTest`、`ApiModelHttpContractTest`、`RequestTraceFilterTest`。

## 3. Lua 准入与订单消费

1. `ACCEPTED` 到底承诺什么？HTTP 超时但 Lua 已成功时，同用户/活动重试为何不能生成第二单？TTL 到期后呢？
2. Lua 的原子执行为什么不等于命令失败回滚？为什么预检类型、把 XADD 放在扣库存前？能直接迁到 Redis Cluster 吗？
3. 活动时间、资格和状态分别来自哪里？这里是否实现了学生身份认证或严格即时状态撤销？
4. 为什么 `XREADGROUP >` 不能恢复旧 pending？新 UUID consumer 如何扫描、claim？为何需要游标与有界批次？
5. 为什么尝试次数在 DB 调用前增加？claim idle 过短会怎样？owner 检查能撤回已执行 SQL 吗？
6. MySQL 提交成功、ACK 失败会怎样？Redisson 用户锁、事务、条件扣库存与唯一约束各保护哪个边界？
7. 同业务键但不同订单 ID 为什么不能按普通幂等成功返回？
8. 达到重试上限后为什么不直接返库存？DLQ、原 ID redrive 与受认证运维身份分别是什么？
9. Redis Stream 替换为 RabbitMQ/RocketMQ 后，Redis 受理到 MQ 发布的双写如何处理？目前为什么保留 Stream？

核对：[准入笔记](../learning/flash-sale-admission.md)、[消费恢复笔记](../learning/reliable-order-consumption.md)、[ADR 0001](../adr/0001-order-message-broker.md)。源码：[seckill.lua](../../src/main/resources/seckill.lua)、[消费器](../../src/main/java/io/github/frewily/campushub/service/OrderStreamConsumer.java)、[队列适配](../../src/main/java/io/github/frewily/campushub/service/OrderStreamQueue.java)、[落库事务](../../src/main/java/io/github/frewily/campushub/service/impl/VoucherOrderServiceImpl.java)。回归入口：`FlashSaleAdmissionServiceTest`、`FlashSaleRedisScriptIT`、`OrderStreamConsumerTest`、`OrderStreamRedisIT`、`OrderLifecycleMySqlRedisIT`。

回答时画出“DB 已提交/未提交/结果未知”与“ACK 已确认/未确认”的故障窗口。禁止用“Lua 原子”或“用了 MQ”代替跨系统推理。

## 4. 取消与缓存一致性

1. 为什么订单取消、DB 库存和 outbox 要同事务？outbox INSERT 失败时哪些操作回滚？
2. Redis 回补成功而 DB 完成标记失败，下一次如何避免重复加库存？PENDING 与 DONE 为什么不能混用？
3. 租约到期、旧 worker 返回和新 worker 接管交错时，谁有权确认？预算耗尽或活动 key 已过期怎么办？
4. 为什么取消不清一人一单记录？已支付订单、退款和自动超时取消是否实现？
5. 缓存 DEL 放在提交前会发生什么？提交后 DEL 成功为何仍可能有旧 loader 回填？
6. Redis epoch、锁 token 与 DB generation 分别防什么？为何旧 worker 不能恢复旧 epoch？
7. 负缓存遇到后续创建如何失效？热点竞争、Redis 故障为什么不无条件回源 DB？
8. TTL、outbox 与发布栅栏是否保证强一致？epoch 或 marker 独立丢失时，本机测试还能证明什么？

核对：[取消恢复](../learning/order-cancellation-recovery.md)、[缓存一致性](../learning/shop-cache-consistency.md)。源码：[生命周期](../../src/main/java/io/github/frewily/campushub/service/OrderLifecycleService.java)、[取消恢复器](../../src/main/java/io/github/frewily/campushub/service/OrderCancellationReconciler.java)、[CacheClient](../../src/main/java/io/github/frewily/campushub/utils/CacheClient.java)、[失效 outbox](../../src/main/java/io/github/frewily/campushub/service/ShopCacheInvalidationService.java)。回归入口：`OrderCancellationReconcilerTest`、`OrderCancellationRedisIT`、`ShopCacheCommitBoundaryTest`、`ShopCacheRedisIT`、`ShopCacheMySqlRedisIT`。

## 5. 搜索、诊断与性能

1. 名称子串查询与全文检索有什么区别？MySQL 为什么是当前基线，什么时候才重新评估 ES？
2. COUNT 与分页为何采用单请求快照？它能保证用户多次翻页期间数据不变吗？距离是否步行路线？
3. 候选索引是否一定生效？如何取得代表性 SQL、EXPLAIN 和实际延迟证据，而不是凭字段猜索引收益？
4. 33 条业务 outcome 测量的是什么？handler、ACK、归档、补偿确认为何不能相加当唯一订单数？
5. scrape 为什么只读内存？计数重启清零、采样间进程更换和零流量时有什么歧义？有 lag/backlog 监控吗？
6. ready、Prometheus 的 up、HTTP 错误和业务状态各回答什么？requestId 能关联全部异步链路吗？
7. 慢接口诊断如何结合请求、JVM/GC、线程、连接池与数据库证据？VU10 尾延迟上升是否足以确定连接池瓶颈？
8. k6 VU、在线用户数和到达率分别是什么？闭环模型为何不能证明生产峰值容量？
9. 为什么三轮 P95/P99 不能直接平均或拼成合并分布？原始 JSON 是聚合汇总还是逐请求样本？
10. 两个不同接口是否缓存优化前后对照？Docker k6 负计时为什么整体拒绝，换原生后能否说根因已修复？
11. Phase 6E2 的有限合成批次验证了什么？20/200 账号、每人两次请求、半库存的结果如何与 Redis、Stream、DB 账本对应？哪些指标不能解释为持续 QPS/TPS、容量或逐单落库延迟？零非预期错误为什么不等于都建单？

核对：[搜索](../learning/shop-search-baseline.md)、[业务诊断](../learning/business-diagnostics.md)、[Phase 6C 只读性能报告](../performance/README.md)、[Phase 6E2 有限抢购批次报告](../performance/flash-sale.md)与[阶段记录](../refactor/21-phase-6e2-flash-sale-results.md)。源码：[搜索服务](../../src/main/java/io/github/frewily/campushub/service/ShopSearchService.java)、[SQL](../../src/main/java/io/github/frewily/campushub/mapper/ShopMapper.java)、[业务指标注册](../../src/main/java/io/github/frewily/campushub/config/BusinessMetricsConfiguration.java)、[只读 k6 脚本](../../scripts/load/baseline.js)、[只读性能驱动](../../scripts/run-performance-baseline.py)。回归入口：`ShopSearchSqlContractTest`、`ShopSearchMySqlIT`、`BusinessMetricsConfigurationTest`、`BusinessRecoveryDiagnosticsTest`；真实诊断由 `scripts/verify-business-diagnostics.py` 验证。

## 6. 复现与诚实边界

先使用 Java 8 运行默认套件（不需要 DB/Redis）：

```bash
./mvnw -Dtest=FlashSaleAdmissionServiceTest,OrderStreamConsumerTest,OrderLifecycleServiceTest,OrderCancellationReconcilerTest,CacheClientTest test
```

显式 IT、自建 Compose 和原生 k6 的环境要求、完整入口以 [仓库 README](../../README.md)、[性能报告](../performance/README.md) 为准。只在自己新建的合成环境中练习故障；不要读取个人验证码/会话、向共享库写 fixture 或清空已有数据。

最后必须能说明：已复现哪些层次、哪些只有历史阶段记录、哪些仍未验证。Phase 6E2 已实际运行有限合成抢购批次，不应继续称“尚未进行抢购实测”；Phase 6C 仍是只读历史基线。6E2 的 batch request rate 不是持续 QPS/TPS，20/200 不是同步起跑或容量；k6 退出到观察到订单完成含 CLI/轮询等开销，不是逐单落库延迟。零非预期错误也不代表每个请求都建单。仍未验证真实短信、历史迁移、SIGKILL 关键窗口、多副本、任意网络分区、HA 或生产 SLO，也没有性能提升百分比结论。
