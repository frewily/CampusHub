# 业务诊断指标：测到了什么，没有测到什么

## 注册与采集边界

业务指标使用 Micrometer FunctionCounter 读取服务实例持有的 LongAdder。固定四个 family、固定枚举 `outcome`，共 33 条业务序列；另有既有的 `application` 公共标签及 Prometheus 的 `job`/`instance` 标签。不接受用户提供的标签，不包含门店、用户、活动、订单、consumer、请求 ID、异常类、原始 URL、token 或验证码。

注册发生在 `SmartInitializingSingleton` 回调：所有非懒加载单例准备好后再接入计数。若普通 MeterBinder 创建时直接依赖 Redis 业务服务，会形成 registry → binder → Redis/Lettuce metrics → registry 的循环。没有开启循环依赖，也没有关闭 Lettuce 指标。

每次 scrape 只读取内存；没有 SELECT COUNT、XPENDING 或 Stream 扫描。指标注册和采集不会承担修复业务状态的任务。初始值为 0；多个计数的读取不是事务快照，并发时为近似观察。实例/JVM 更换后清零，数据重启后仍在不代表内存计数仍在。

## 指标词汇

| Prometheus family | 固定 outcome | 含义与限制 |
| --- | --- | --- |
| `campushub_shop_cache_events_total` | `first_hit`、`first_negative_hit`、`first_miss` | 只计合法请求的第一次缓存读取分类；锁内二次检查与等待后命中不再作为首次 hit。首次读取失败不会伪造 miss。 |
| 同上 | `load_started`、`publish_rejected`、`unavailable_signal` | loader 调用尝试、发布被 epoch/lease 栅栏拒绝、CacheClient 产生 unavailable 信号。load 不等于成功建缓存；信号包含查询、主动事务拒绝、失效失败，不是独立请求失败数量，也不含所有上层 BusinessException。 |
| `campushub_orders_consumer_events_total` | `handler_returned`、`acknowledged` | 业务 handler 正常返回（含幂等已存在等路径）、owner-checked SUCCESS 返回 1。两者可能因 ACK 故障产生差值，且重投递会重复计数，不是新增订单数量。 |
| 同上 | `deferred`、`archived`、`stale`、`transition_failure`、`poll_failure`、`unconfirmed` | FAILURE 返回 0 保留 pending、返回 1 归档并 ACK、失去 owner、单记录编排异常、运行中轮询基础设施异常、未定义的确认结果。正常停机后的轮询异常不计为 poll failure。归档不等于自动回补库存。 |
| `campushub_shop_invalidation_events_total` | `callback_invalidated`、`callback_failed` | DB 提交后 Redis 回调完成/失败；回调从不在原事务连接上确认 outbox，因此前者不代表 outbox 已删除。 |
| 同上 | `completed`、`retry_recorded`、`stale`、`entry_failure`、`poll_failure`、`unconfirmed` | generation CAS 删除成功、失败信息 CAS 更新成功、CAS 影响行 0、单条恢复异常、拉取异常、非 0/1 的返回。completed 只在 DB 操作返回 1 后计数；合并写入意味着事件数不是商户修改数。 |
| `campushub_orders_cancellation_events_total` | `released`、`already_released`、`expired` | Lua 返回 0/1/2 后，持有租约的 DB complete 影响行 1。即使 Redis 已回补，DB 确认失败也不增加这三项；重放可能由 already_released 完成确认。 |
| 同上 | `claim_missed`、`exhausted`、`retry_recorded`、`review_recorded`、`stale`、`entry_failure`、`poll_failure`、`unconfirmed` | claim 0、预算耗尽转 review 的 CAS 成功、retry 更新确认、review 更新确认、CAS 0、单条异常、拉取异常、未定义的确认结果。claim 0 同时计入 claim_missed 与 stale，不能把所有 outcomes 相加当处理总数；retry 在第十次可能已落为 REQUIRES_REVIEW。 |

这些计数不是 queue lag、pending 数量、outbox backlog、年龄、缓存服务可用率或订单成功率。没有变化可能是没有流量、worker 禁用/停机、采集失败或尚未触发对应边界，不能单凭零值判断队列为空。诊断时要结合 `up`、ready、HTTP 错误和持久化状态；需要 backlog/年龄时应另设有界异步采样、超时、freshness 与失败语义，不能在 scrape 内直接查询依赖。

## PromQL 示例（不是实测结论或告警阈值）

首次缓存读取命中率的分子包含正/负命中。用相同窗口计算，每实例先 rate 再 sum；分母为零时不要展示百分比：

```promql
sum(rate(campushub_shop_cache_events_total{outcome=~"first_hit|first_negative_hit"}[5m]))
/
sum(rate(campushub_shop_cache_events_total{outcome=~"first_hit|first_negative_hit|first_miss"}[5m]))
```

错误观察示例：

```promql
sum by (outcome) (rate(campushub_orders_consumer_events_total{outcome=~"deferred|archived|transition_failure|poll_failure|unconfirmed"}[5m]))
sum by (outcome) (increase(campushub_orders_cancellation_events_total{outcome=~"entry_failure|poll_failure|review_recorded|exhausted"}[15m]))
```

Prometheus rate/increase 会处理观测到的 counter reset，但两次采样之间发生又随进程消失的事件不能保证被收集。合成验收中的命中与故障样本仅证明链路和语义，不代表真实业务命中率、性能基线、告警阈值或生产 SLO。

当前仍是单主机、单业务副本、loopback 管理面。尚未验证多副本、SIGKILL 故障窗口、DB/Redis HA、网络分区、历史库迁移及实际压测。质量盘点与本阶段实跑结果分别见 [覆盖矩阵](../refactor/phase-6b-quality-matrix.md)、[阶段记录](../refactor/17-phase-6b-business-diagnostics.md)。
