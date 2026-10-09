# Phase 4A 门店详情缓存治理

## 范围、证据与取舍

本阶段仅治理 `GET /shop/{id}` 的门店详情，以及门店创建/修改导致的详情缓存失效。保留 HTTP 路由、Result 包装、商户权限与门店归属。分类列表、GEO、搜索、用户、Feed 和活动运行态不混入这条详情缓存策略；Phase 4B 未开始。不加入专业 MQ、Elasticsearch 或性能指标平台。

基线 `68e5e1b` 的 `updateShop` 在事务内直接 DEL，数据库还没提交；回滚也已删缓存。新用例 `ShopCacheCommitBoundaryTest.updateMustNotInvalidateRedisBeforeCommitOrOnRollback` 在旧实现实际失败，指出唯一 Redis 交互来自 updateShop。旧重建没有版本栅栏、锁值恒为 1 且直接 DEL、递归重试与模拟延迟；正式逻辑过期值没有物理 TTL，异步旧读可能在更新失效后重新写入并长时间残留。这些后续风险来自完整调用链与读写时序检查，未把它们冒称为每个缺陷均执行过旧实现回归。

选择 **Cache Aside + 物理 TTL/抖动 + 有界互斥重建 + epoch 栅栏 + 事务失效 outbox**。取消无限期旧值返回和后台逻辑过期线程池，不再保留另一条正式或实验写入策略。`RedisData` 和 ShopService 内的旧递归/手工预热代码已移除；手工预热实验改走正式读取入口。旧学习阶段的测试数量/行为仍是历史记录，本阶段替换相关逻辑过期测试，不据此宣称性能提升。

取舍：故障或热点竞争时显式返回 503，不无限递归、返回无限期旧值或放任所有请求直接回源。冷请求需要等待数据库读取，250ms 只是竞争轮询预算，并不限制单个 SQL 或网络调用总时长。10 秒重建租约过期后旧发布被拒，长查询可能返回 503。参数均为未调优默认值。

## 唯一正式读策略

| Key | 作用 | 生命周期 |
| --- | --- | --- |
| `cache:shop:v2:<id>` | JSON envelope：epoch、empty、data | 正值 60～75 秒；空值 10～15 秒 |
| `cache:shop:epoch:<id>` | 失效版本 UUID | 不自动过期，只对创建/修改的门店产生 |
| `lock:shop:v2:<id>` | 重建 owner UUID | 10 秒租约，token 比对释放 |
| `cache:shop:<id>` | 旧格式 | 新读取不使用；新写失效时定点清理 |

读 Lua 原子取得 epoch/data 快照。有效 envelope 命中直接返回；空值命中返回不存在。类型错误、非法 JSON、无确认结果或数据库失败返回 `SHOP_STATE_UNAVAILABLE` 503，不捏造 404。

Miss 后获取 SET NX + TTL 的独立 UUID 锁；拿到后再次检查缓存，再回源。失败者每 10ms 轮询最多约 250ms，超预算 503；不执行无锁 DB fallback。发布 Lua 同时确认锁 owner 与最初读取的 epoch，才能以 PX 写入 envelope。失效期间的旧读或租约过期任务不能污染新缓存；解锁 Lua 只删除自己的 token。失效拒绝后尝试在原轮询预算内重新读取/回源，不无限重试。

Cache reader 拒绝活跃数据库事务，防止把未提交数据或 repeatable-read 旧快照写入公共缓存。事务用例需要读库时使用 Mapper / `getById`，不调用公开缓存读取。Redis 不可用时不承诺 DB 降级服务：需要先保护回源容量，不能把所有热点请求转发数据库。

## 写事务与提交后失效

先应用 V004 `tb_shop_cache_invalidation`。每个 shop_id 最多一个 pending 行；每次门店写入用新的 DB generation UUID UPSERT，重置失败计数。该记录与门店写入同一个实际事务，不允许在无事务情况下登记。创建也登记生成的数据库 ID，清掉此前可能存在的空值缓存。更新继续保留既有 merchantId，且检查真实写入结果；失败写入不返回成功或登记失效。

事务回滚：门店与 outbox 一起回滚，不发生 Redis 写入。

提交后：Spring afterCommit 尝试 Redis 失效，失败仅记录安全异常类名，已经提交的数据库修改不能被说成回滚。**回调只操作 Redis，不用仍绑定的旧事务连接写 outbox 完成标记**；pending 行保留给调度器确认。因此正常回调成功后调度器还会进行一次安全重复失效。

`SHOP_CACHE_INVALIDATION_WORKER_ENABLED` 默认 true，沿用已启用的 Spring scheduling，每 5 秒最多读取 32 行，按 update_time/shop_id 索引排序。恢复成功用 `DELETE WHERE shop_id AND generation` 确认；新写入已替换 generation 时旧 worker 不能删掉新事件。失败只记录类名并更新尝试计数（最多记 1000000），更新时间使其他 pending 行有机会推进。重复缓存失效没有返库存副作用，不需要取消补偿的租约或 10 次终结预算；仍有 pending 就按固定周期继续，不是紧密无限循环。worker 禁用不会关闭 afterCommit 的即时尝试。

每次失效调用都生成 **新的 Redis epoch UUID**，不复用 DB generation。旧事件重复处理或 worker 乱序不能把版本恢复到先前值（ABA）。失效 Lua 先 SET epoch，再 DEL v2/旧数据。Lua 没有错误回滚：若 DEL 被 ACL 拒绝，旧 envelope 物理上还在，但 epoch 不匹配，正式读取会忽略并重新加载。若 epoch 写入也失败，outbox 保留等待恢复；期间既有缓存可能仍被使用。

提交与缓存失效不是跨库原子操作。提交至成功失效之间、读取与写入重叠时仍可能看到旧值；本阶段不是线性一致、read-your-writes 或 exactly-once 保证。失效恢复期间缓存本身最多存活 75 秒，但慢数据库查询、依赖不可达和版本混跑意味着不能宣称整个请求或恢复流程有固定时间 SLA。outbox 不自动保证治理、容量、告警和服务可用性。

## 格式迁移与运行边界

新读取使用 v2 前缀，不解析旧的实体 JSON/逻辑过期 envelope，避免格式混用。没有全库扫描或删除旧 key；新写入成功失效只清理该门店旧 key。旧的 no-TTL key 仍可能占用内存，需要受控、列明目标的治理流程。部署前停用旧预热/旧缓存写入程序；双版本混跑不保证失效一致性，不能用新 outbox 修复旧应用继续产生的永久缓存。

epoch key 的基数约为曾创建/修改的门店数，需容量规划和 ACL 保护。不能独立删除/过期/回退 epoch，而让旧 data 或旧 loader 存活；这会削弱版本防护。Redis 当前只支持单机布局，不支持跨槽 Cluster。数据、epoch 和租约的持久化/failover 恢复、OOM、备份一致性、生产时钟/超时均未验收。

V004 支持重复创建，不会纠正已存在的错误表结构；上线要检查字段/索引。SQL 排程使用 UTC，继续保留 JDBC `serverTimezone=UTC&forceConnectionTimeZoneToSession=true`。直接 SQL 改门店、绕过本用例的 IService 通用写接口、第三方脚本不会自动登记 outbox，需另外的受控失效流程；本阶段未引入 CDC。

建议检查 pending 数量、最老 update_time、异常类名与尝试数，以及 epoch/data 的内存占用。这里只规定测量与运维方法，不宣称已经接好告警或指标平台。

## 命中率测量方法

`CacheClient.statistics()` 返回当前进程累计数组，顺序为：正值首次命中、空值首次命中、首次 miss、数据库 loader 次数、发布被版本/锁拒绝次数、不可用次数。每个初次有效快照仅统计一次；竞争后被其他 loader 填好的请求仍归首次 miss，poll 不增加 hit。数据库 loader 次数包括失败和被拒绝发布的尝试。不使用 Redis 全局命中率代替门店请求指标。

在相同实例生命周期、相同采样窗口计算差值：详情缓存首次命中率 = `(Δ正值命中 + Δ空值命中) / (Δ正值命中 + Δ空值命中 + Δ首次miss)`；分母为零不输出比例。单独报告不可用与发布拒绝，不把失败从业务服务可用率中隐藏。累计数组不是事务快照，LongAdder 并发采样为近似；重启清零，多实例需按窗口聚合。Phase 6 才决定正式指标接口。本阶段未采集实际业务命中率，不填百分比。

## 实际验证

使用 JDK 1.8.0_492 与命令行 Surefire 3.1.2，POM 不变。默认 suite 不要求外部服务，手工实验按设计跳过。

最终结果：162 项默认测试，0 失败/错误，4 项手工外部服务用例按设计跳过；72 项隔离集成测试，0 失败/错误/跳过。其中缓存 Redis 13 项、订单 Redis 回归 35 项、缓存 MySQL/Redis 9 项、订单 MySQL/Redis 回归 15 项。

ACL 用例首次整组执行在 finally 清理命令的整数返回解码处失败（不是业务断言失败）；修正为关闭测试私有 ACL 用户并由私有进程退出清理，再完整重跑上述 72 项通过。未掩盖首次失败或把它计入通过证据。

```bash
./mvnw -Dmaven-surefire-plugin.version=3.1.2 clean test
./mvnw -Dmaven-surefire-plugin.version=3.1.2 \
  -Dtest=ShopCacheRedisIT,OrderCancellationRedisIT,OrderStreamRedisIT,FlashSaleRedisScriptIT test
./mvnw -Dmaven-surefire-plugin.version=3.1.2 \
  -Dtest=ShopCacheMySqlRedisIT,OrderLifecycleMySqlRedisIT test
```

Redis IT 拥有独立无持久化进程、随机 localhost 端口。覆盖正负命中、物理过期、TTL 抖动、旧格式隔离、20 路热点冷 miss、旧读/新失效、旧 token、版本变化、重复失效不 ABA、损坏状态 fail closed，以及真实 ACL 在 SET epoch 后拒绝 DEL 的部分失败恢复。这个 20 路测试使用合成 loader，不是 MySQL 吞吐或生产命中率证明。

MySQL 9.6.0 + Redis 8.6.2 IT 使用私有 datadir、无开发密码和合成 schema。真实 Mapper + Spring TransactionInterceptor 覆盖提交后失效、外层回滚、真实 INSERT trigger 故障回滚、Redis 故障仍保留已提交事实、恢复及 DB CAS、创建清空值缓存、读写交错与 V004 正向重复执行。该缓存业务链 IT 不安装 method-security，也不启动真实网络 HTTP；既有默认权限合同另行回归，不混淆证据范围。

目标 MySQL 8/Redis 6 兼容、完整历史数据迁移、真实网络部署、安全/持久化故障演练和性能仍未验收。**尚未进行实际压测**，不提供 QPS、延迟改善或实际命中率。

取舍与追问见 [缓存一致性学习说明](../learning/shop-cache-consistency.md)。
