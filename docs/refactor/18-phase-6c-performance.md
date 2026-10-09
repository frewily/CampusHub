# Phase 6C：可复现的小规模性能基线

日期：2026-10-09。运行时代码基线：Phase 6B 提交 `48f9d0629a91c5c550a54a9f89ca2c9ff513523c`。本阶段不修改 Java 业务或正式部署配置，也不升级 Java 8 / Boot 2.7.18。

**当前状态：选定范围的实现与本机验证完成，按流程提交后复审。** 经用户确认采用临时官方 macOS ARM64 k6 v1.3.0，一次完整原生测量、独立结果复核及测试资源清理通过。两次被拒绝的 Docker 计时结果不用于报告，根因仍未确认。只读小基线不是生产容量、活动抢购压测或工具异常根因已修复的证明。

## 实现范围

- 官方 macOS ARM64 k6 v1.3.0 release，归档 SHA-256 `eb06b22418e26f7394023e53aaaddf0bec739f669acf718cc9b0e2d7f12bd7be`；由 `scripts/prepare-native-k6.py` 准备到临时路径，harness 经 `K6_BINARY` 校验可执行文件哈希。宿主机原生负载进程经本轮 Compose 随机发布端口访问固定 `127.0.0.1`；JS 仅接受 1–65535 的数值 `TARGET_PORT`，拒绝 `BASE_URL`。仅门店正缓存详情与 MySQL 搜索两类读接口。
- 1/10 VU，四个组合各三轮；每轮 5s 独立预热、20s 正式测量，gracefulStop/HTTP timeout 均 5s；闭环、无 think time、连接复用。
- 新增独立性能 overlay，运行容器 CPU/内存限制断言；确定生成 1000 行合成门店，不读个人 `.env` 或共享服务。k6 设 `GOMAXPROCS=1`、`GOMEMLIMIT=256MiB` 软内存/GC预算，不设置或声称 Docker CPU、RAM、PID 硬配额；应用、MySQL、Redis、Prometheus 配额不变。
- 真实响应合同、零 HTTP/业务错误、迭代完整性、指标存在/数值边界、缓存计数窗口、新鲜 Prometheus 样本、逐文件 SHA-256 和发布去敏门槛。
- k6 进程使用私有空配置和最小环境，不读取用户配置且禁用遥测；超时后清理直接子进程，driver 清理 Compose 项目。清理和全部测量通过后才发布 24 个未修改的 k6 聚合汇总与 manifest；不是逐请求原始样本。原始命令/应用日志不公开。
- 新增性能解释和事实限定的面试材料，不宣称本人独立开发、生产容量或性能提升。

## 验证入口

```bash
./mvnw clean package
python3 -m unittest discover -s scripts -p test_performance_baseline.py
export K6_BINARY="$(python3 scripts/prepare-native-k6.py)"
python3 scripts/run-performance-baseline.py
```

本轮原生路线使用 Corretto 1.8.0_492 / Maven 3.9.9 离线缓存，重新执行 `clean package` 默认回归 255 项，0 失败/错误，4 项教学实验按设计跳过；package 成功。未改生产业务，所以不把 Phase 6B 的 82 项隔离 IT、8/10/5 组业务/部署/采集验收写成本轮重跑；本阶段另在新项目上跑真实目标版本只读负载和采集窗口检查。

本轮最终 23 项离线校验测试通过，覆盖 native flat/nested summary、缺失指标、计数和耗时的 NaN/Infinity/负值/布尔、错误响应、缺少检查、不完整迭代、avg 和分位数边界、全部 HTTP timing 分项及 tagged series 的负值、严格三轮分组、各轮中位数计算、raw 字节/哈希保留、清理门槛、禁止覆盖、隐私词匹配和路径越界。原生路线新增归档校验与单成员提取/禁止覆盖、固定 binary hash/绝对路径、最小环境不继承代理/Cloud 选项、失败/超时不伪装成功测试。

在已校验的原生 k6 上实际执行 inspect：正常 TARGET_PORT/场景可解析；9 组缺失/非法端口、外部 BASE_URL、超范围 VUS/DURATION/CASE 都在请求之前被拒绝。Luna 运行前只读审查未发现确定的测量、安全或清理阻断项。上述检查仍不替代完整负载实跑。

最终原生驱动退出 0：12 次正式测量与 12 次预热全部通过，共 689737 次正式请求、0 HTTP/业务错误、全部响应检查通过。MySQL 8.4.11 / Redis 6.2.24 / Prometheus 3.5.0 实际运行；容器 JVM 为 Temurin 1.8.0_504，宿主构建 JVM 为 Corretto 1.8.0_492。四个组合的逐轮 RPS/P95/P99 中位数与范围、实际硬件/资源及原始链接集中在 [性能报告](../performance/README.md)。

独立复核检查 24 份公开文件与私有原始输出逐字节一致、SHA-256 一致、全部 HTTP timing 数值/顺序及平均值边界合法、零错误、VU/迭代/检查数与记录一致、12 个缓存窗口严格符合所选路径、四组统计重新计算一致、jar/脚本/overlay/support 哈希一致。四类测试项目资源由脚本清理，独立查询无残留；基础镜像保留。Python 编译、文档链接、敏感格式检查和 `git diff --check` 通过；提交后仍需对实际提交及原始结果复审，不提前声明该复审已完成。

## 调查与审查边界

按系统化调试先追踪接口序列化，不直接把两个端点的 ID 统一：详情保留数字 ID，搜索 DTO 返回字符串 ID；k6 按各自实际契约检查。排序值为小写 `price_asc`，不放宽 Java 参数验证。

Luna 只读审查发现汇总校验器最初未断言平均耗时落在 min/max 内，另指出公开扫描文案不能比实际词匹配范围更宽。先补回归，确认旧校验器拒绝测试失败，再补 avg/P90 边界和 session/cookie 词匹配，收窄文档承诺。该问题属于结果校验的防御边界，不是已发现的 Java 性能缺陷；修正后再运行完整最终驱动，不在测量中途改脚本。早期结果移到私有诊断目录保留，不作为最终基线提交。

审查后第一次完整 Docker k6 驱动在第一组正式测量被既有非负门槛拒绝：`http_req_duration.min=-1.036305ms`、`http_req_receiving.min=-1.215305ms`，但 89298 次响应检查与迭代完成。该轮无公开结果且资源已清理，没有把负数置零、删除样本或放宽校验。k6 v1.3.0 的 [客户端 tracer 源码](https://github.com/grafana/k6/blob/v1.3.0/lib/netext/httpext/tracer.go) 用 UnixNano 保存事件时间，并用重新构造的 wall-clock 时间计算接收耗时；还有先取结束时间、再读取可能异步更新的回调时间的窗口。这些路径可能产生负值，但未获得实际时钟跳变/回调时序证据，不能写成已确认根因。保留私有日志，并在相同最终脚本、参数与非负门槛下做一次有界完整复核；结果也不构成已修复工具/虚拟机计时问题的证明。

有界 Docker 复核完成全部 12 组并清理后，独立检查 24 个 raw 文件发现 `shop_detail_warm-vu10-r3.json` 的 `http_req_sending.min=-0.123973ms`。虽然总 duration 为正，分项异常仍使本批数据不可靠，因此撤回整个待提交结果目录，不选择性删一轮或替换样本。新增先失败的回归并增强所有 HTTP timing 分项/tagged series 校验，当时 18 项通过。该测试项目容器、卷、网络、应用镜像由脚本清理，独立只读检查亦无残留；基础镜像仍保留。此异常仍未确认根因。随后经用户确认采用宿主机原生 k6 的 localhost 发布端口路径，与旧容器共享网络 namespace 的路径不同；`GOMAXPROCS=1` 与 `GOMEMLIMIT=256MiB` 是运行时设置，不等同于 Docker CPU/RAM/PID 硬限制。新路径完整测量通过，但不能据此声称已消除旧异常或构成同条件性能提升对照。

参数、限制与最终原生结果由 [性能报告](../performance/README.md) 给出。本报告以各组合三轮的逐轮 RPS/P95/P99 中位数和范围表示，不存在 pooled percentile、优化前后对照或提升比例。失败 Docker 原始记录仍保留在私有诊断目录，不公开日志、不清洗异常、不复用为有效数字。

## 未覆盖范围

不代表峰值、生产用户、开放到达率、长稳态、冷盘、冷缓存、读写竞争、全量搜索维度或活动抢购负载；没有真实短信、历史库迁移、多副本、进程关键窗口强杀、网络分区、HA 或生产 SLO 验收。本机的资源竞争和短时 JIT/缓存状态仍影响结果，不能凭单个耗时峰值认定瓶颈。

所有小阶段仍按“验证 → 提交 → 提交后审查”收口；后续产品范围或生产化路线须另行决定，不在本阶段顺带加入新中间件、UI 或生产优化。
