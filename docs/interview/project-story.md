# CampusHub 项目故事：从遗留系统到可验证的后端

日期：2026-10-10。实现基线：`4882a20348ed3d2e7b87a6fe761bb7da2ebf36ae`。

这是项目的技术叙事，不是个人独立完成声明。项目来源于黑马点评教学工程，迁移与重构由 AI 辅助完成；只有亲自读通、复现并能回答追问的部分，才能作为自己的面试经历。未掌握的部分应明确说正在接管学习。

## 1. 为什么重构

目标不是隐藏教程来源，而是把遗留代码变成可运行、可测试、能解释失败路径的校园生活与周边商户服务后端。初始审计发现匿名写入、Feed key 错误、关注 Redis 类型不一致、事务失败仍 ACK、缺少业务唯一约束等确定性问题，见 [初始审计](../refactor/00-current-state.md)。

因此先建立 Java 8 的可复现构建与测试入口，再修正确性、补权限与 API 边界，最后做部署、故障补验、指标和小范围性能测量。没有一次性重写，也没有为了技术栈名称强行引入微服务、专业 MQ 或 Elasticsearch。

## 2. 当前到底是什么项目

当前是单 Maven 模块的 Spring Boot 后端，Java 根包为 `io.github.frewily.campushub`；主要源码仍按 Controller/Service/Mapper 等技术层组织。按业务模块分包是 [目标架构](../refactor/01-target-architecture.md) 中的演进方向，不能说已经完成模块化分包迁移。

角色和门店归属已经实现，但完整商户后台、管理后台、评论/评价系统、消息通知和学生身份认证没有交付。保留 `Shop`、`Blog`、`Voucher`、旧路由和表名是兼容取舍，不通过全局改名宣称业务已经全面升级。当前也没有完成验收的前端或系统截图。

MySQL 保存订单、门店和关注等业务事实；Redis 承载可撤销会话、门店缓存、Feed/GEO、签到与活动运行态。遗留点赞仍依赖 Redis ZSet 与数据库计数，不能声称所有 Redis 数据都有完善的持久化关系及重建方案。

## 3. 四条值得讲清楚的调用链

### 身份与资源权限

`UserController → UserServiceImpl → 验证码 Lua / MySQL → Redis 会话`；请求经过 `RedisTokenAuthenticationFilter`，核对会话、数据库账号状态和角色，再进入业务授权。结束时清理 SecurityContext 与 UserHolder，避免线程复用污染。

当前使用随机 opaque token 与 Redis 滑动过期，不是 JWT，也没有独立 refresh token 协议。商户写操作还要核验有效商户成员和门店归属；仅有 MERCHANT 角色不能修改所有门店。ADMIN 不允许经普通参与接口抢购。验证码有冷却、失败预算与原子消费，但未接短信供应商；合成验收读取的是测试自己 Redis 中的验证码，不是产品的短信发送能力。

证据：[会话生命周期](../refactor/07-phase-2b-session-lifecycle.md)、[权限阶段](../refactor/08-phase-2c-authorization.md)、[目标版本业务验收](../refactor/17-phase-6b-business-diagnostics.md)。源码：[认证过滤器](../../src/main/java/io/github/frewily/campushub/security/RedisTokenAuthenticationFilter.java)、[资源授权](../../src/main/java/io/github/frewily/campushub/security/ResourceAuthorizationService.java)。

### 限量活动受理与可靠消费

`VoucherOrderController → FlashSaleAdmissionService → seckill.lua → stream.orders → OrderStreamConsumer → VoucherOrderServiceImpl → MySQL`。

Lua 检查活动、时间、资格、库存和重复受理，成功返回预生成订单 ID 与 `ACCEPTED`。这只代表 Redis 已受理，不代表数据库建单完成。同用户/活动的重试在辅助记录保留期间取回原 ID，不能因 HTTP 超时就断定未受理。

消费者采用 UUID 名称，扫描 pending 并按空闲阈值 claim；处理前持久记录尝试次数。事务内条件扣库存与插单，数据库 `(user_id, voucher_id)` 唯一约束提供最终防线；同订单 ID 重投递不重复扣库存，不同 ID 冲突不能当成功。MySQL 提交和 Redis ACK 不在同一事务，所以必须接受重投递并实现幂等，而不是宣称 exactly-once。

耗尽预算后归档失败记录并保留预扣，不自动猜测数据库是否提交。运维核对后使用原 ID 前向重放，不把 DLQ 等同于退款指令。保留 Redis Stream 的理由与重新选型条件见 [ADR 0001](../adr/0001-order-message-broker.md)；单机 Redis 仍是共享故障域，Lua 不提供命令出错回滚、跨槽 Cluster 或断电持久化保证。

证据：[准入](../refactor/10-phase-3a-flash-sale-admission.md)、[可靠消费](../refactor/11-phase-3b-reliable-order-consumption.md)、[恢复取舍](../learning/reliable-order-consumption.md)。源码：[准入服务](../../src/main/java/io/github/frewily/campushub/service/FlashSaleAdmissionService.java)、[消费器](../../src/main/java/io/github/frewily/campushub/service/OrderStreamConsumer.java)、[数据库持久化](../../src/main/java/io/github/frewily/campushub/service/impl/VoucherOrderServiceImpl.java)。

### 本人订单查询与取消

`VoucherOrderController → OrderLifecycleService → MySQL 订单/库存/outbox → OrderCancellationReconciler → Redis Lua → DB 租约/CAS 确认`。

只允许本人查询与取消已落库未支付订单。订单状态、数据库库存和取消 outbox 在同一事务内改变；Redis 回补由后台恢复，HTTP 不把“取消成立”和“补偿完成”混成一个状态。Redis DONE marker 防重复加库存，PENDING 表示部分执行不确定，需要核对。Redis 已释放但 DB 确认失败时，后续重试仍须幂等。

取消不清一人一单记录，本人不能靠取消重新报名。当前没有支付、退款、核销和自动超时取消链路；活动运行态过期后也不擅自重建库存。

证据：[订单阶段](../refactor/12-phase-3c-order-lifecycle.md)、[补偿笔记](../learning/order-cancellation-recovery.md)。源码：[订单生命周期](../../src/main/java/io/github/frewily/campushub/service/OrderLifecycleService.java)、[取消恢复](../../src/main/java/io/github/frewily/campushub/service/OrderCancellationReconciler.java)。

### 门店详情缓存与搜索

门店详情采用 Cache Aside、负缓存、物理 TTL 抖动、有界 token 互斥和 epoch 发布栅栏。门店写入与失效 outbox 同事务，提交后尝试 Redis 失效，独立 worker 确认/重试；旧 loader 不能在失效后无条件回填旧值。它仍有 DB 提交到 Redis 失效的窗口，不是跨系统强一致。

搜索接口单独直接查询 MySQL，支持名称子串、类别、价格、评分、距离和固定排序分页。没有中文分词、相关性或索引同步链；[ADR 0002](../adr/0002-shop-search-engine.md) 选择先建立 MySQL 基线，没有证据就不声称 ES 必需。

证据：[缓存治理](../refactor/13-phase-4a-shop-cache-governance.md)、[搜索基线](../refactor/14-phase-4b-shop-search.md)。源码：[CacheClient](../../src/main/java/io/github/frewily/campushub/utils/CacheClient.java)、[失效恢复](../../src/main/java/io/github/frewily/campushub/service/ShopCacheInvalidationService.java)、[搜索服务](../../src/main/java/io/github/frewily/campushub/service/ShopSearchService.java)。

## 4. 如何证明，不把测试层次混用

- 默认测试：纯逻辑、mock 依赖与 HTTP 合同，不证明真实数据库行为。
- 显式 IT：自建本机 MySQL/Redis 与合成数据；版本与 Docker 目标环境不同。
- 隔离 Compose 验收：唯一项目、随机 localhost 端口、合成凭据与全新卷；真实目标依赖、HTTP、迁移、故障和实际 Prometheus 采集，见 [Phase 5](../refactor/15-phase-5-engineering.md)、[Phase 6B](../refactor/17-phase-6b-business-diagnostics.md)。
- Phase 6C 只读基线：原生 k6 的两种只读请求，1/10 VU、每组合三轮、各轮独立 5 秒预热/20 秒测量；保存原始聚合汇总和哈希，见 [性能报告](../performance/README.md)。这是历史只读结果，不是抢购压测、生产容量或优化前后对照。
- Phase 6E2 有限抢购批次：使用 20/200 个合成账号各三轮，每账号两次 HTTP 请求，初始库存为账号数的一半。六个正式批次共 1320 次请求，分类为 330 次新受理、330 次原 ID 重放、660 次售罄；六轮彼此独立的预热另有 120 次请求和 30 笔订单。原始 k6 汇总的 outcome/check 计数与 manifest 一致，正式批次账本记录 330 笔新订单、330 个 Stream 事件、库存归零、pending/dead entries 为零，且每轮不变量检查通过。见[有限批次报告](../performance/flash-sale.md)、[Phase 6E2 记录](../refactor/21-phase-6e2-flash-sale-results.md)及 [manifest](../performance/flash-sale-results/20261010T041031Z/manifest.json)。

6A/6B 的“当时尚未压测”是历史阶段边界；6C 仍是只读基线。6E2 只证明本轮有限合成批次中 HTTP 分类和 Redis→Stream→DB 账本按预期收敛：batch request rate 不是持续 QPS/TPS，20/200 是有限执行批次而非同步起跑或容量；零非预期错误不等于每个请求都建单。k6 退出到观察到订单完成的时间包含 CLI/轮询等开销，不是逐单落库延迟。没有真实短信、关键窗口强杀、多副本/HA、SLO 或性能提升百分比结论；也不能把优雅重启说成强杀恢复，或把单次 scrape/ready 说成业务健康。

## 5. 学习接管出口

先选一条链路读源码，再复现对应测试并解释至少一个故障窗口，最后脱稿回答 [追问清单](questions.md)。记录实际做过的工作与尚不理解的点；测试通过或 AI 生成说明不代表本人掌握。

准备 [简历要点](resume-points.md) 时只采用已经亲自理解和验证的部分。项目仍有 [交付缺口](../refactor/19-phase-6d-delivery.md)，不能把“选定阶段完成”写成“所有原始目标或生产验收完成”。
