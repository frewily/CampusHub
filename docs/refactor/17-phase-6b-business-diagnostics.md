# Phase 6B：核心业务质量补验与诊断指标

日期：2026-10-09。基线：Phase 6A 最终提交 `928fd88e3201484e95eef713deb18f1dfbc0bfb2`。

本阶段只做业务计数语义和核心链路的合成故障补验，不进入 6C 压测。当前验证状态见下文，提交后复审完成才进入下一小阶段。

## 1. 实现范围

- 四类 process-local FunctionCounter：缓存首次 hit/negative hit/miss、加载与发布栅栏、订单 handler/ACK/延后/归档、缓存失效 outbox、订单取消 outbox。共 33 个固定 outcome 序列，不使用业务 ID、consumer、异常类或请求数据标签。
- `DiagnosticCounters` 用固定枚举与 LongAdder；不改服务构造器、授权、事务、Lua、重试策略和数据库结构。成功边界在 Redis transition=1 或 DB CAS 影响行=1 后计数；0 行视为 stale，其他未定义结果单列，异常不会计为成功。
- 注册使用 `SmartInitializingSingleton`；scrape 只读内存，不查 MySQL/Redis。重投递、幂等正常返回和多边界观察不等于新增订单数；没有 lag、backlog、outbox 年龄或生产命中率结论。
- 默认测试新增启动生命周期、固定标签/并发计数、CAS0/确认异常、轮询与单记录故障隔离的回归。
- `verify-business-diagnostics.py` 使用唯一 Compose 项目、随机 localhost 发布端口、全新合成卷；目标 MySQL/Redis、真实 HTTP 过滤器/方法权限、业务服务/事务/Lua、实际 Prometheus。不会读取个人 `.env`、连接共享库或发送真实短信。
- 故障只有合成库 trigger、合成 Redis epoch 类型和独立应用/collector 优雅停止重启。取消租约过期由合成 SQL 加速，不假称已等待自然 30 秒窗口或演练 SIGKILL。
- 保留既有公共接口与 Java 8/Boot 2.7.18/Micrometer 1.9.17。不引入 ES、专业 MQ、Grafana、付费服务或负载工具。

## 2. 验证入口与证据

```bash
./mvnw -Dtest=BusinessMetricsConfigurationTest,BusinessRecoveryDiagnosticsTest,CacheClientTest,OrderStreamConsumerTest,ShopCacheInvalidationServiceTest,OrderCancellationReconcilerTest test
./mvnw clean test
./mvnw -Dtest=ShopCacheRedisIT,OrderCancellationRedisIT,OrderStreamRedisIT,FlashSaleRedisScriptIT,ShopCacheMySqlRedisIT,OrderLifecycleMySqlRedisIT,ShopSearchMySqlIT test
./mvnw -DskipTests package
python3 scripts/verify-business-diagnostics.py
```

已确认的本轮结果（Corretto 1.8.0_492 / Maven 3.9.9，离线依赖缓存）：

- 定向 46 项通过，0 失败/错误/跳过。启动回归启用实际 Lettuce metrics、RedisConnectionFactory 和 Prometheus registry，禁止循环依赖，但不连接 Redis；另外三业务源使用 mock。
- 最终 `clean test`：255 项，0 失败/错误，4 项教学实验设计跳过。默认套件不需要外部 DB/Redis；包含真实随机本机 HTTP 监听器的 mock 依赖合同。
- 最终 7 个显式 IT 套件：82 项，0 失败/错误/跳过。各测试自行启动独立 MySQL 9.6.0 / Redis 8.6.2 进程和合成数据，不等于目标版本完整运行。
- package 成功；Python 编译检查和 `git diff --check` 通过。
- 最终新目标版本 Compose/Prometheus 验收 8 组全部通过，进程退出 0：MySQL 8.4.11 / Redis 6.2.24 / Prometheus 3.5.0。此前失败/部分 PASS 的轮次不计为成功。
- 既有 Phase 5 部署 10 组与 Phase 6A 私有管理面/实际采集 5 组在当前 jar 上重新通过，均退出 0；覆盖启动守卫、图片/权限/持久化、迁移、依赖停机及采集隐私，没有把历史结果当本轮重跑。

脚本实际通过的八组出口：真实验证码消费/登录/权限；关注唯一性/Feed；缓存统计及 outbox 故障恢复；活动创建/重放/订单归属/ACK；Redis 已释放但 DB 确认失败的取消恢复；持久化失败 pending 与 worker 重启；新鲜 Prometheus 样本/隐私/进程计数重置；禁用账号/登出。重启后的采样时间必须晚于重启开始，缺失序列返回 NaN，不能被当作计数为 0。

原始日志、合成验证码/会话与 JSON 报告仅保留在权限受限的临时目录，不能加入 Git。脚本 finally 清理确切测试项目的容器、卷、网络和应用镜像，并检查四类残留；基础镜像保留。

## 3. 定位过的失败

遵循系统化调试：先读取错误、定位组件边界，再作小修正并重跑，不把失败轮次当完成证据。

1. Micrometer registry 有 close 方法，但当前版本不是 AutoCloseable；新测试的 try-with-resources 编译失败。使用既有测试相同的 finally.close，没有升级依赖来绕过。
2. 普通 MeterBinder 直接依赖 Redis 业务服务导致 registry → binder → Lettuce metrics → registry 初始化循环，真实容器启动失败。改为所有单例完成后的注册，补实际 Lettuce 自动配置/禁止循环依赖的回归；没有开启循环依赖或关闭 Lettuce 指标。
3. 合成账号含 ADMIN 时，既有规则禁止其参与活动，网络验收返回 403。脚本增加拒绝/库存不变断言，在合成 DB 撤销其 ADMIN 角色后再以 USER 下单；没有放宽授权。
4. mysql CLI 按分号拆开复合 trigger，故障注入语句 1064 失败；给该 CLI fixture 明确 DELIMITER 边界。生产业务 SQL 不变。
5. 优雅重启时随机发布端口重新分配，旧地址的等待 ready 超时，容器自身已 healthy；按既有部署回归方式重新发现业务/collector 端口，最终完整脚本从头通过。

## 4. 未覆盖的范围

- 合成验证码从本测试自己的 Redis 读取，不验证真实短信、第三方服务或个人账号。ADMIN 角色由合成 SQL fixture 授予/撤销，不代表存在产品角色管理接口。
- 每实例计数重启清零，采样间消失的事件可能丢失；多个计数不是事务快照。没有给出总成功率/失败率、唯一订单数量或 backlog 数量。
- 优雅重启不等于事务关键窗口强杀；trigger 故障不等于网络半开/丢响应、任意延迟、MySQL/Redis failover、磁盘满、生产 HA 或多副本验收。
- 默认/本机 IT/目标 Compose 是不同证据层级；仅选定合成业务流程在目标版本验证，不宣称全部旧业务跨版本兼容、历史库升级或真实数据迁移安全。
- 尚未进行实际压测，没有 QPS/P95/P99、性能提升比例、生产缓存命中率、告警阈值或生产 SLO。

固定指标与解释见 [业务诊断笔记](../learning/business-diagnostics.md)；覆盖盘点见 [质量矩阵](phase-6b-quality-matrix.md)。后续 6C 需单独选择负载工具、明确本机资源与参数并保存原始结果，不能把本次正确性样本转写为性能报告。
