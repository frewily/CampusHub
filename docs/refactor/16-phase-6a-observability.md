# Phase 6A：可观测性基线

日期：2026-10-09。基线：Phase 5 最终提交 `db877ff552dc1d81b8f8667769a5989500e91e09`。

本小阶段完成请求编号、安全摘要日志、独立管理面、HTTP/JVM/连接池指标及实际 Prometheus 采集验收。Phase 6B/6C 未开始；没有实际压测，不宣称 Phase 6 全部完成。

## 1. 实现范围

- 引入 Boot 2.7.18 自带版本管理的 Actuator、Micrometer 1.9.17 Prometheus registry，保持 Java 8，不手动跨版本升级依赖。
- Actuator 使用独立 IPv4 loopback 监听器，默认 `127.0.0.1:8082`，业务端口仍为 8081。启动守卫拒绝非 loopback、非法端口、相同非零业务/管理端口和变更 base path；管理端口 0 用于测试的独立随机监听器。
- endpoints 默认 disabled，仅显式启用 health/prometheus；专用 EndpointRequest 安全链只放行对应两个 GET，其他 endpoint/方法不放行。不把业务 Redis Token 当采集凭据，不暴露 env/configprops/heapdump/loggers/shutdown。
- Actuator health 复用现有 ReadinessService（SELECT 1 + 认证 PING），不返回 details/components；原 `/health/live`、`/health/ready` 契约保留。它不是表结构、消费进度或完整业务探针。
- Micrometer 采集 HTTP server timer/histogram、JVM、HikariCP。HTTP uri 使用 MVC 路由模板，最多 100 个 uri 值，超预算新增序列被丢弃；非标准 method 合并为 UNKNOWN。没有 requestId、用户 ID、手机号、订单 ID、token 或原始查询标签。
- 禁用当前未使用的 HTTP client 请求指标：测试客户端的字面 URI/查询可能污染其标签。今后如引入出站 HTTP client，要先建立安全模板策略再启用。
- RequestTraceFilter 在业务认证之前生成 UUID，忽略外部 X-Request-Id，写响应头/MDC并在 finally 恢复上下文。新摘要只记录有限 method、模板 route、观察到的 status、dispatch durationMs、dispatchFailed，不记录原 URI、查询、头、body 或异常消息。
- `compose.monitoring.yaml` 显式 opt-in：Prometheus 3.5.0 以 UID/GID 65534:65534 运行，共享 app 网络 namespace，直接采 `127.0.0.1:8082/actuator/prometheus`，无需发布管理端口。UI 9090 仅映射到宿主 loopback；非 root、只读 rootfs、无额外 capabilities。
- Prometheus 单独 named volume，24h/256MB retention 参数只用于本地观测；不是严格的全目录硬配额（head/WAL等仍占空间）、备份或 HA。没有 Grafana、远程告警平台或付费服务。
- 新真实采集脚本复用已有合成部署验收的私有日志与精确清理机制；旧部署脚本的本机应用管理端口改为随机 0，防止占用固定端口。

## 2. 实际验证

使用 Corretto 1.8.0_492、Maven 3.9.9。新依赖的 Java/Maven HTTPS 下载曾出现超时和握手失败；确认公开 Maven Central 地址有效后，补齐并校验官方 SHA-1 缓存，再执行以下离线构建。没有关闭 TLS 校验、改依赖版本或把临时下载工具加入项目。

```bash
./mvnw -Dtest=ObservabilityConfigurationTest,ObservabilityHttpContractTest,RequestTraceFilterTest test
./mvnw clean test
./mvnw -Dtest=ShopCacheRedisIT,OrderCancellationRedisIT,OrderStreamRedisIT,FlashSaleRedisScriptIT,ShopCacheMySqlRedisIT,OrderLifecycleMySqlRedisIT,ShopSearchMySqlIT test
./mvnw -DskipTests package
python3 scripts/verify-compose.py
python3 scripts/verify-observability.py
```

结果：

- 新增 15 项定向测试通过：配置/标签预算 5、真实私有双 HTTP 监听器的合同 5、请求编号/MDC/安全摘要 5。
- 完整默认套件 244 项：0 失败/错误，4 项设计跳过。不需要外部 MySQL/Redis；其中 HTTP 合同使用 mock 业务依赖及真实随机本机端口，不能视为真实 DB 验收。
- 既有 7 个显式隔离 IT 套件重新运行：82 项，0 失败/错误/跳过；本机 MySQL 9.6.0、Redis 8.6.2。
- package 成功；Phase 5 的 10 组全新部署回归再次通过，包括本机 jar、prod 缺配置拒绝、图片/权限/重启、迁移和依赖停机路径。
- 实际 Prometheus 3.5.0（arm64 Docker 镜像）5 组验收通过；目标依赖仍为 Phase 5 的 MySQL 8.4.11 / Redis 6.2.24。本地 Engine 29.6.1 / Compose 5.3.0。

实际采集检查：

1. promtool 验证配置，Prometheus 查询 `up{job="campushub"} == 1` 有真实样本，Compose 未发布 8082。
2. 业务端口匿名请求 Actuator prometheus/env/heapdump 均为 401，不提供指标或敏感端点。
3. 响应使用服务端 UUID而非客户端 ID；成功、400、401 的 HTTP 计数和 histogram buckets 进入 Prometheus。
4. 查询实际 JVM/Hikari 序列；采集样本不含合成查询标记、客户端 ID、请求 UUID、测试密码。新摘要不输出查询标记或客户端 ID，完整日志行的 MDC 仍包含服务端 UUID，用于关联排查。检查只验证所列 fixture 与新摘要，不等于全仓库任意日志已全面脱敏。
5. Redis 停机后业务 ready 503/DOWN、live 200；观察停机后新的 scrape timestamp 且 up 仍为 1，证明 exporter 可达不代表依赖健康。恢复 Redis 后 ready 重新 UP。

所有实际部署使用唯一合成项目、随机 localhost 发布端口、全新数据卷，不读自己的 `.env`，不连接共享业务库。最终清理后按确切项目检查容器、卷、网络、应用镜像均为空；保留基础镜像。私有原始日志/结果 JSON 留在脚本输出的临时目录，不进入 Git。

## 3. 失败定位与修正

- 当前 Micrometer registry 有 close 方法但不是 AutoCloseable，测试编译揭示 try-with-resources 不适用，改为 finally 中关闭；不是升级依赖来绕过。
- Boot SpringBootTest 默认禁用 metrics exporter，最初 prometheus 未注册而返回 401；检查实际测试 customizer/自动配置后，只在该 HTTP 合同测试加 AutoConfigureMetrics，生产安全规则不放宽。
- 隐私测试揭示测试 RestTemplate 产生 HTTP client 指标，包含调用者字面查询。按指标 family 定位后禁用未使用的 client 请求指标，保留服务端模板指标，原隐私断言继续通过。
- 新 Python 脚本最初把带连字符的旧脚本名当普通模块导入，未进入部署即失败；使用明确文件路径的 importlib 加载。
- monitoring overlay 中 collector 依赖带 profile 的 app，最初日志读取遗漏 profile 导致 invalid compose project。将 collector 同属 app profile，且验收全程显式 app profile，再运行整套采集与清理检查。
- 提交后复审修正文档中“日志不含请求 UUID”的不准确表达：指标不含 UUID，日志 MDC 有 UUID 是设计的一部分。补充双监听器 HTTP 合同，明确管理端口不会返回普通公开业务路由的成功响应。

以上按系统化调试流程定位，失败记录不当作通过结果。

## 4. 能力边界与后续

- localhost 管理接口与 Prometheus UI 没有额外用户认证，同主机用户/共享 namespace collector 必须可信。不是公网/跨主机采集、TLS 或生产认证拓扑；不要通过代理或额外端口转发暴露管理面。应用重建时应一起重新建立共享 namespace 的 collector。
- 请求 UUID 不是分布式 trace；不自动传播到异步 worker。摘要耗时只测当前 dispatch，不保证异步结束耗时；异常冒泡时 status 可能早于容器最终响应，dispatchFailed 是单独信号。本文不宣称所有异常日志已全面整改。
- 业务缓存/抢购/消费/outbox 诊断指标、完整质量缺口验证、负载工具、告警阈值与面试材料属于后续 6B/6C。当前无消费 lag 或缓存命中率的生产采样结论。
- buckets、启动 smoke 请求和测试样本不是性能基线。尚未进行实际压测，没有发布 QPS/P95/P99 或性能提升比例。
- 新监听器/过滤器/指标会占用额外资源，但当前没有测量开销。Java 8 / Boot 2.7 的遗留支持风险仍在。

学习解释与 PromQL 示例见 [可观测性基线](../learning/observability-baseline.md)。按阶段流程提交后复审完成再进入 6B。
