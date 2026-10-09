# Phase 6B 核心质量覆盖盘点

盘点对象是当前 Java Test/IT 源码、验收脚本与仓库阶段记录。矩阵起草为静态源码检查，不产生实跑证据；主代理随后执行了本轮默认/IT/目标 HTTP 验收，具体结果集中在 [Phase 6B 阶段记录](17-phase-6b-business-diagnostics.md)。下文区分测试设计、历史实测与本轮实测，不把源码存在、历史通过或静态审查当作本次运行结果。

## 覆盖矩阵

下表列出既有 Java Test/IT 的分层设计；6B 新增真实 HTTP 脚本的补充范围与实跑证据在后文及阶段记录，不把 Java mock 层的限制泛化到整个项目。

| 领域 | 现有测试文件与检查内容 | 证据层级与边界 |
| --- | --- | --- |
| 登录与验证码 | `src/test/java/io/github/frewily/campushub/service/impl/UserServiceImplTest.java` 检查发码冷却、验证码消费、登录失败计数/限流、成功登录建会话、禁用账号、角色初始化和登出。`src/test/java/io/github/frewily/campushub/security/RedisTokenAuthenticationFilterTest.java` 检查 token 读取、角色/账号状态装载、请求上下文清理和禁用账号会话失效。 | Service 与 Redis/Mapper mock；Filter 单测亦使用 mock。不是网络登录，不覆盖完整验证码登录链路。 |
| HTTP 认证与权限 | `src/test/java/io/github/frewily/campushub/config/SecurityHttpContractTest.java` 用 MockMvc 检查匿名拒绝、管理员/商户/用户路由和资源归属契约；`src/test/java/io/github/frewily/campushub/controller/OrderLifecycleHttpContractTest.java` 检查订单查询/取消的角色、状态码、参数和响应；`src/test/java/io/github/frewily/campushub/security/AuthorizationPolicyTest.java` 检查端点策略声明；`src/test/java/io/github/frewily/campushub/security/ResourceAuthorizationServiceTest.java` 与 `src/test/java/io/github/frewily/campushub/service/impl/ShopServiceAuthorizationTest.java` 检查商户成员身份及资源归属。 | 大部分为 mock/MockMvc，不创建 TCP HTTP 监听。`src/test/java/io/github/frewily/campushub/service/OrderLifecycleMySqlRedisIT.java` 中 `httpAdmissionReadAndCancelUseRealTokenFilterRedisAndDatabase` 使用私有 Redis、真实 mapper/业务链和 MockMvc；它覆盖合成 token，不覆盖真实登录。 |
| Feed 与关注 | `src/test/java/io/github/frewily/campushub/service/impl/BlogServiceImplTest.java` 检查新笔记 fan-out 写入各关注者自己的 Feed ZSet；`src/test/java/io/github/frewily/campushub/service/impl/FollowServiceImplTest.java` 检查共同关注的 ZSet 读取、重复关注幂等与 Redis 投影修复、自关注拒绝。 | Mapper 与 Redis 操作 mock。当前未发现 Feed 的真实 Redis IT、真实 HTTP Feed 读写或跨实例 Feed 一致性测试。 |
| Shop 缓存 | `src/test/java/io/github/frewily/campushub/utils/CacheClientTest.java` 检查冷热/空值缓存、TTL 抖动、错误 envelope、epoch、互斥重建、竞争、事务边界和 fail-closed；`src/test/java/io/github/frewily/campushub/utils/ShopCacheRedisIT.java` 对私有 Redis 执行真实脚本，检查正负缓存、过期、并发 miss、读失效竞态、旧 lease/epoch、错误 key 类型及 ACL 部分失败；`src/test/java/io/github/frewily/campushub/service/ShopCacheInvalidationServiceTest.java` 与 `src/test/java/io/github/frewily/campushub/service/impl/ShopCacheCommitBoundaryTest.java` 检查 mock 事务提交/回滚边界。 | CacheClientTest 和事务边界测试使用 mock；ShopCacheRedisIT 是本机独立 Redis 进程，不是 Compose。与真实 mapper、DB 事务组合的证据见下方 outbox IT。 |
| 活动准入 | `src/test/java/io/github/frewily/campushub/service/FlashSaleAdmissionServiceTest.java` 检查接受/重放、Lua 结果映射、身份/资格/活动校验、异常和不确定结果 fail-closed；`src/test/java/io/github/frewily/campushub/service/FlashSaleRedisPublisherTest.java` 与 `src/test/java/io/github/frewily/campushub/service/impl/VoucherServicePublicationTest.java` 检查活动规则发布与事务边界；`src/test/java/io/github/frewily/campushub/service/FlashSaleRedisScriptIT.java` 在私有 Redis 执行真实 Lua，覆盖库存竞争、同用户重放、规则状态、错误 key/Stream 类型及 XADD 失败。 | Service/publisher 测试使用 mock；脚本 IT 使用本机独立 Redis。该 IT 不包含真实 MySQL 事务；真实 DB/Redis 受理链由 `OrderLifecycleMySqlRedisIT` 中的组合用例补充。 |
| Stream 消费 | `src/test/java/io/github/frewily/campushub/service/OrderStreamConsumerTest.java` 检查 pending 恢复编排、毒消息隔离、数据库异常不 ACK、ACK 异常不误判为数据库失败、重试预算、owner stale 和 ID 校验；`src/test/java/io/github/frewily/campushub/service/OrderLifecycleServiceTest.java`、`src/test/java/io/github/frewily/campushub/service/impl/VoucherOrderServiceImplTest.java` 检查订单业务及状态边界。`src/test/java/io/github/frewily/campushub/service/OrderStreamRedisIT.java` 对私有 Redis 检查 PEL 接管、idle 门槛、预算、DLQ、stale owner、分页、redrive 和 Spring worker 接管。 | Consumer/service 单测使用 mock；Stream IT 的 Redis 为本机独立进程，Spring worker 用例中的订单处理服务是 mock。真实 MySQL 持久化与 Redis Stream 一起验证见 `src/test/java/io/github/frewily/campushub/service/OrderLifecycleMySqlRedisIT.java`。用例模拟新 consumer/worker 接手，不等同于强杀单独运行的应用进程。 |
| 订单取消与补偿 | `src/test/java/io/github/frewily/campushub/service/OrderCancellationReconcilerTest.java` 检查 claim、预算耗尽、Redis 结果、重试/review、完成写入失败与 disabled worker；`src/test/java/io/github/frewily/campushub/service/OrderCancellationRedisIT.java` 在私有 Redis 执行补库存 Lua，检查 64 位订单 ID、幂等重放、并发只回补一次、错误状态/类型、ACL 中断和 marker 恢复；`src/test/java/io/github/frewily/campushub/controller/OrderLifecycleHttpContractTest.java` 检查 HTTP 契约。 | Reconciler/HTTP 单测使用 mock；Redis IT 只验证真实 Redis 脚本。`src/test/java/io/github/frewily/campushub/service/OrderLifecycleMySqlRedisIT.java` 组合真实 DB、Redis、mapper、事务与服务，覆盖取消、库存回补、并发、outbox 和 lease 场景。 |
| DB outbox 与迁移 | `src/test/java/io/github/frewily/campushub/service/ShopCacheInvalidationServiceTest.java` 检查 mock CAS、Redis 失败、提交后回调及 disabled worker；`src/test/java/io/github/frewily/campushub/service/ShopCacheMySqlRedisIT.java` 用私有 MySQL/Redis 进程和真实 mapper/事务，检查提交/回滚、outbox 插入触发器故障、Redis 失败后恢复、旧 generation CAS、重复处理和 V004 重跑。`OrderLifecycleMySqlRedisIT` 覆盖取消 outbox、DB 回滚、Redis 已释放但 DB 未确认、lease owner 替换、UTC 查询与 V001–V003 重跑。`src/test/java/io/github/frewily/campushub/db/DatabaseMigrationTest.java` 检查迁移 SQL 的结构文本及幂等声明。 | MySQL/Redis IT 使用各自创建的本机进程、随机 localhost 端口和合成 schema；不是 Compose，也不加载历史业务库。`DatabaseMigrationTest` 是静态 SQL 断言，不执行 SQL。合成 schema 上重复迁移不能证明真实历史数据升级安全。 |

## 测试运行层级与证据来源

| 层级 | 当前证据 | 能说明什么 / 不能说明什么 |
| --- | --- | --- |
| Mock 与 MockMvc | 上表中的 service、mapper、Redis mock 测试；Spring MockMvc HTTP contract。 | 可验证分支、请求/响应、过滤器与授权契约；MockMvc 不监听网络，mock 依赖不证明真实 Redis/MySQL 行为。 |
| 本机独立服务 | `ShopCacheRedisIT`、`FlashSaleRedisScriptIT`、`OrderStreamRedisIT`、`OrderCancellationRedisIT` 启动私有 Redis；`ShopCacheMySqlRedisIT`、`OrderLifecycleMySqlRedisIT` 启动私有 MySQL 和 Redis，使用随机 localhost 端口与临时目录。 | 覆盖真实 Redis Lua、真实 SQL mapper/事务及组合故障。服务生命周期由测试代码管理，数据库是合成 schema；这不是 Compose 部署，也不是应用多副本网络运行。 |
| Compose 与真实 HTTP | `scripts/verify-compose.py` 创建唯一临时 Compose 项目，启动真实 MySQL/Redis/app，通过 HTTP 检查健康、门店和权限/上传；重复执行迁移并检查已填充数据库拒绝 bootstrap；重启 app/MySQL 检查文件卷和 DB 数据持久性；停止/恢复 Redis 检查 readiness 与会话；最后清理该项目的容器、卷、网络和应用镜像。`scripts/smoke-test.sh` 是其中调用的只读 health/readiness/search 检查，也可单独针对已运行地址执行。`compose.monitoring.yaml` 提供显式启用的 Prometheus。 | 这是实际部署验收脚本，不是静态配置检查。它具备真实网络 HTTP 和依赖重启/故障路径；但本次盘点作者没有运行它。该脚本当前不覆盖 6B 计划中的验证码登录、Feed、订单消费/outbox 专项故障及多实例。 |

当前 `pom.xml` 配置 Surefire，未配置 Failsafe；这些 `*IT.java` 集成测试需显式选择运行。源码存在不等于默认测试阶段已执行。

| 证据归属 | 仓库中可核对的记录 | 使用边界 |
| --- | --- | --- |
| 静态盘点过程 | 只读核对上述 Java 测试、`scripts/verify-compose.py`、`scripts/smoke-test.sh`、Compose 配置和阶段文档。 | 静态检查自身不产生运行结果；本轮另行执行的结果见 Phase 6B 阶段记录。 |
| 仓库既有阶段实测 | `docs/refactor/16-phase-6a-observability.md` 记录 Phase 6A 实际重跑 Phase 5 的 **10 组全新部署回归**，以及实际 Prometheus **5 组验收**；并记录私有 Compose 项目、随机本机端口、合成数据和精确清理边界。该记录还描述了健康检查、权限/上传、迁移、app/MySQL 重启、Redis 停机及恢复等验收。 | 这是仓库记载的 Phase 5/6A 历史结果，不是本次重跑，更不是 Phase 6B 登录/Feed/消费/outbox 脚本的实测。此矩阵不把这些历史记录改写成 6B 完成证据。 |

## 待补证据与计划项

- **Phase 6B 真实 HTTP 补验（本轮已执行）：** `scripts/verify-business-diagnostics.py` 在唯一私有 Compose 中读取合成验证码，实际通过登录/权限、Feed、cache/outbox 故障、消费失败与优雅 worker restart。八组结果及目标版本见阶段记录。此项补充的是表中 Java mock/MockMvc 的真实网络业务路径，不是短信服务、任意网络故障或强杀测试。
- **网络故障与调用结果不确定：** `verify-compose.py` 的既有设计包含 Redis 停止时 ready 降级、恢复后会话可用，以及 MySQL 重启后数据保留；Phase 6A 文档记录了该部署回归实跑。Java IT 还覆盖 ACL/脚本部分写入和本地服务组合故障。尚无证据覆盖完整应用遭遇任意连接重置、网络延迟/半开连接、持续超时或更复杂依赖故障注入后的恢复策略。
- **进程强杀：** Java IT 有 pending 接管、提交后未 ACK、Redis 已完成但 DB 未确认等新 worker 恢复语义；Compose 脚本有 app/MySQL restart 的持久性检查。前者通过新对象/worker 模拟，后者是 Compose restart，不等价于在 DB/Redis 操作关键窗口强杀真实应用 JVM 并验证事务、PEL、租约和恢复结果。
- **多实例与部署切换：** 现有 Java Test/IT 有线程并发、owner/lease CAS 和 worker 替换用例；既有 `verify-compose.py` 验收也检查单应用容器的重启/依赖恢复。尚无证据覆盖多个独立应用进程/Compose 副本同时消费、滚动升级、负载均衡或跨实例故障演练。
- **历史迁移：** `verify-compose.py` 与 MySQL/Redis IT 在合成、测试数据库执行迁移/重复迁移；Phase 6A 记录也包含该 Compose 路径的实际验收。它们不能覆盖真实历史数据、已有错误结构、生产规模 backfill、升级/回滚窗口或跨版本混跑。V004 的 `IF NOT EXISTS` 也不证明旧表结构会被修复。
- **生产 HA 与耐久性：** 当前 IT 的 Redis 禁用持久化，MySQL/Redis 均为私有单实例。Redis Sentinel/Cluster/failover、MySQL HA/故障切换、持久化回放、备份恢复、OOM/磁盘满、跨可用区网络分区、真实 ACL/TLS 与容量/告警/SLO 尚未验收。
- **性能与兼容矩阵：** 并发测试检查正确性，不代表 QPS/P95 或生产负载能力；Compose 中声明的镜像版本也不代表本次已运行验证。尚需对目标 Redis/MySQL 版本、资源限制与真实部署拓扑单独验收。

## 结论边界

现有测试覆盖多个关键业务分支；Phase 6B 已补充选定登录/Feed/消费/outbox 的目标版本真实 HTTP 专项验收，并重跑本机隔离 IT 与既有部署/采集。矩阵主体描述 Java 测试设计，实跑以阶段记录为准。关键窗口强杀、多应用实例、任意网络分区、真实历史库升级、生产 HA 和性能仍未验证；不据此作“全部旧业务完成验收”或生产可靠性结论。
