# CampusHub 可观测性基线：边界、指标与证据

本文梳理 Phase 6A 当前可从配置和实现中确认的可观测性设计，并说明如何解读后续 Prometheus 数据。它描述的是基线与验证方法，不是压测报告或生产可用性结论。

## 两个监听器与管理面边界

业务 HTTP 服务默认监听 `8081`；Spring Boot Actuator 管理服务单独监听 `127.0.0.1:8082`。管理监听器只绑定 IPv4 loopback，且与业务端口分离。`ObservabilityConfiguration` 注册启动期守卫：要求管理地址精确为 `127.0.0.1`、端口配置合法、非零管理端口不与业务端口相同，并固定 `/actuator` base path；配置不符合约束时启动失败。

管理安全链由 `EndpointRequest.toAnyEndpoint()` 限定在 Actuator endpoint。仅 `GET /actuator/health` 和 `GET /actuator/prometheus` 放行，其它 endpoint、其它 HTTP 方法都拒绝。普通业务安全链仍单独处理业务路由。`application.yaml` 将 Actuator endpoint 默认设为 disabled，只显式启用 `health` 与 `prometheus`；Health 不返回 details 或 components。

HealthIndicator 直接读取现有 `ReadinessService` 的 `ready()` 结果，因此 Actuator health 沿用应用 readiness 判定：ready 时为 `UP`，否则为 `DOWN`。它不会把依赖详情展开给匿名调用者。业务侧 `/health/live`、`/health/ready` 是独立的应用路由，不要把它们和管理监听器上的 Actuator health 混为一谈。

## Micrometer 指标与标签预算

Micrometer HTTP server timer 开启 Prometheus histogram buckets。Prometheus 导出名形如 `http_server_requests_seconds_count`、`http_server_requests_seconds_bucket`。HTTP `uri` 标签应使用 Spring MVC 匹配到的路由模板，例如 `/shop/search`，不能把用户输入的原始路径或查询参数作为维度。`http.server.requests` 的 `uri` 标签设置了 100 个值的最大预算，超过预算的新增 tag 会被拒绝，以限制高基数增长。

HTTP method 标签经 `MeterFilter` 限定在 `GET`、`POST`、`PUT`、`PATCH`、`DELETE`、`HEAD`、`OPTIONS`、`UNKNOWN`。其它 method 映射成 `UNKNOWN`。通用 Micrometer/Actuator instrumentation 还提供 JVM 与 HikariCP 指标；验证脚本会查询 `jvm_memory_used_bytes` 和 `hikaricp_connections`，但脚本中的预期检查本身不等于已通过的运行证据。

Phase 6A 明确只采集 HTTP server 指标。标签诊断发现，Spring Boot 为测试用 `TestRestTemplate` 仪表化出的 `http_client_requests` 指标可能把测试调用者提供的字面 URI 和查询串带进 client `uri` 标签；这与服务端 `http.server.requests` 使用的正确路由模板标签是两类数据。当前应用源码没有 `RestTemplate`、`WebClient` 等真实出站 HTTP client，`application.yaml` 因此设置 `management.metrics.enable.http.client.requests: false`，避免测试调用者地址混入本阶段 scrape。未来若引入真实出站 client，应先制定并验证安全的 URI 模板/标签策略，再决定启用 client 指标。

Histogram quantile 必须对真实采样窗口中的 bucket 增量先做 `rate`，再保留 `le` 聚合。进程刚启动时的累计 bucket 值不是压测样本，也不能据此声称 P95/P99 性能结果。低请求量或窗口内样本很少时，分位数和错误比例都不稳定；应增加可复现请求量、明确负载持续时间，并报告窗口与请求数。

下面是查询范例，窗口可按负载持续时间调整：

```promql
# 过去 5 分钟的总 QPS
sum(rate(http_server_requests_seconds_count{application="campushub"}[5m]))

# 过去 5 分钟的 5xx 比例
sum(rate(http_server_requests_seconds_count{application="campushub",status=~"5.."}[5m]))
/
sum(rate(http_server_requests_seconds_count{application="campushub"}[5m]))

# 全部路由的近似 P95 / P99，单位为秒
histogram_quantile(0.95,
  sum by (le) (rate(http_server_requests_seconds_bucket{application="campushub"}[5m])))

histogram_quantile(0.99,
  sum by (le) (rate(http_server_requests_seconds_bucket{application="campushub"}[5m])))
```

错误比例在窗口内没有请求时分母为零，结果不可解释；流量很低时也不要把一个或几个请求当成稳定比例。P95/P99 是窗口内直方图的估算值，不是单次请求耗时，也不是自动生成的压测结论。

## 本地 Prometheus sidecar

`compose.monitoring.yaml` 是显式加入的本地监控 overlay。Prometheus 使用 `network_mode: service:app` 与应用共用网络 namespace，因此可以访问应用的 `127.0.0.1:8082` 管理监听器；Prometheus 配置也以 `127.0.0.1:8082/actuator/prometheus` 为 scrape target。管理端口不需要映射到宿主机。Prometheus UI 的 `9090` 只映射到宿主机 `127.0.0.1`。

该 sidecar 配置 24 小时与 256 MB retention 参数，不是整个目录的硬配额，head/WAL 等仍可能额外占空间。这是短期本地观测留存，不是备份、灾备或高可用方案；overlay 没有配置 Grafana。共享网络 namespace 意味着 Prometheus 容器可以访问应用 loopback 上的管理服务，所以 collector 镜像、配置、运行环境和操作者都必须可信。localhost 接口没有额外用户认证，同主机用户也必须可信；不要通过代理或端口转发把管理面暴露出去。这个拓扑仅用于受控本地验证，不能直接照搬成 production central collector 的部署模型。

## 请求追踪日志与异常边界

`RequestTraceFilter` 为首次处理的请求生成服务端 UUID，忽略客户端 `X-Request-Id`，把新 ID 写入响应头和 MDC，并在链路结束后恢复原有 MDC；原来没有 MDC 时会清理上下文。摘要只记录白名单 method（其他值为 `UNKNOWN`）、`HandlerMapping.BEST_MATCHING_PATTERN_ATTRIBUTE` 路由模板（缺失时 `UNMATCHED`）、观察到的 response status、dispatch 毫秒耗时和 `dispatchFailed`。不记录原始 URI、查询串、请求头、token、body、用户 ID 或手机号。

`dispatchFailed=true` 表示 filter chain 抛出了未处理的 `IOException`、`ServletException` 或 `RuntimeException`；异常原样继续传播，filter 不改写 response，也不记录异常消息。日志中的 status 是 dispatch 结束时观察到的值，异常冒泡后容器可能才把最终客户端状态改成 500，因此该字段不保证等于最终响应状态。

耗时只覆盖当前 servlet dispatch，不等待异步请求完成。MDC 是线程上下文；异步执行切换到其它线程时，requestId 不会自动跨越该边界传播。当前基线没有实现 async completion 计时或 MDC 跨线程传播。

## 当前范围与后续证据

本基线覆盖管理面隔离、有限 endpoint 暴露、HTTP server/JVM/Hikari 指标、请求摘要和本地 Prometheus scrape 设计。业务专用指标、可复现压测和面试材料仍待完成；不能据此宣称 Phase 6 全部完成。

真实 HTTP 合同测试使用两个随机本机监听端口，业务依赖为 mock；全新 Compose 的实际采集使用合成数据库与 Redis。两种证据边界不同，也都不等于压测。阶段实施和运行结果以 [`docs/refactor/16-phase-6a-observability.md`](../refactor/16-phase-6a-observability.md) 为准。

实现依据：[`ObservabilityConfiguration`](../../src/main/java/io/github/frewily/campushub/config/ObservabilityConfiguration.java)、[`SecurityConfig`](../../src/main/java/io/github/frewily/campushub/config/SecurityConfig.java)、[`application.yaml`](../../src/main/resources/application.yaml)、[`RequestTraceFilter`](../../src/main/java/io/github/frewily/campushub/observability/RequestTraceFilter.java)、[`compose.monitoring.yaml`](../../compose.monitoring.yaml)、[`prometheus.yml`](../../deploy/prometheus/prometheus.yml) 与 [`verify-observability.py`](../../scripts/verify-observability.py)。阶段运行证据以 [`docs/refactor/16-phase-6a-observability.md`](../refactor/16-phase-6a-observability.md) 记录为准；本文用于说明设计边界与 PromQL 解读，不代替阶段结论。
