# Phase 6C：本机只读性能基线

本文保留 6C 的读取基线与历史边界。后续 2026-10-10 的限量活动有限批次实测单独见 [6E2 报告](flash-sale.md)，不与本表混合、不用读取 RPS 替代订单 TPS。

**当前状态：选定范围的原生只读基线已完成。** 2026-10-09 原生路线一次完整执行通过，保留 12 次正式测量与 12 次预热的原始聚合汇总，并完成独立复核及测试资源清理。此前两次 Docker k6 测量因计时负值撤回，未用于本报告；根因仍未确认。没有生产容量、活动抢购性能或优化提升结论。

这是一份小规模、同宿主机、闭环请求的可复现基线，不是峰值容量、真实用户模型、生产 SLO、限量活动压测或性能优化前后对照。本阶段不改 Java 业务、数据库 schema、缓存策略、依赖版本或正常部署资源参数。

## 本次实测结果

正式测量共完成 689737 次请求，HTTP/业务错误均为 0，响应检查全通过；预热也通过校验。以下均为相同用例/VU 的 **三轮逐轮指标中位数 [最小–最大]**，耗时单位毫秒，不是合并请求分布的百分位数。

| 用例 | VU | RPS | 各轮 P95 (ms) | 各轮 P99 (ms) |
| --- | --- | --- | --- | --- |
| 正缓存详情 | 1 | 2303.43 [2198.20–2367.06] | 0.458 [0.456–0.501] | 0.707 [0.694–0.774] |
| 正缓存详情 | 10 | 7241.88 [7220.96–7351.04] | 1.577 [1.560–1.579] | 25.993 [25.869–26.356] |
| MySQL 搜索 | 1 | 801.48 [796.70–816.99] | 1.456 [1.192–1.625] | 2.083 [1.888–2.198] |
| MySQL 搜索 | 10 | 1127.98 [1114.86–1137.36] | 70.565 [70.137–70.929] | 78.737 [78.547–80.271] |

原始证据：[manifest.json](results/20261009T144219Z/manifest.json)、[24 份未修改汇总](results/20261009T144219Z)。manifest 列出每轮对应的正式/预热文件名、完整数值和 SHA-256；目录名为 UTC 发布时刻。两个接口不是缓存前后对照，VU=10 的尾延迟上升也不足以证明具体 CPU/连接池/数据库瓶颈。

实际环境：Apple M5 / Mac17,3，10 核、16 GiB RAM，macOS 27.0.1 arm64；Docker Engine 29.6.1 aarch64，VM 配额 10 CPU/8321515520 字节 RAM，Compose 5.3.0。宿主构建为 Corretto 1.8.0_492 / Maven 3.9.9，**应用容器实际为 Temurin 1.8.0_504-b01**；两者不能混写。服务为 MySQL 8.4.11、Redis 6.2.24、Prometheus 3.5.0。原生工具实际输出 `k6 v1.3.0 (commit/5870e99ae8, go1.25.1, darwin/arm64)`。运行时代码 revision 为 `48f9d0629a91c5c550a54a9f89ca2c9ff513523c`，jar、工具、脚本和 overlay 标识以 manifest 的哈希为准。

独立复核确认 24 个公开文件与私有原始文件逐字节一致、全部 HTTP timing 总量/分项/tagged series 有限非负、检查数/VU/迭代数与 manifest 一致，12 个窗口的缓存计数和四组合统计可重算，脚本及 jar 哈希一致。脚本清理后另查测试项目容器、网络、卷和应用镜像，四类均无残留；基础镜像保留。

## 用例与负载模型

| 固定用例 | 请求与执行路径 | 数据/窗口约束 |
| --- | --- | --- |
| `shop_detail_warm` | `GET /shop/10001`，正式 Cache Aside 正缓存详情读 | 每组独立删除合成门店缓存并请求预热。测量前后新鲜 Prometheus 样本要求 `first_hit` 增量严格等于请求数，其余 5 类缓存事件增量均为 0 |
| `shop_search_mysql` | `GET /shop/search?keyword=synthetic-baseline&sort=price_asc&page=1&size=10`，MySQL count + 分页 | 匹配 1000 行、返回 10 项；直接访问 MySQL，6 类详情缓存事件增量都必须为 0 |

全新数据库含默认演示门店 1 行和确定生成的合成门店 1000 行，总计 1001 行。新增 ID 为 `10001..11000`，名字为 `synthetic-baseline-0001..1000`，type=1、坐标 (120,30)、均价 `20 + i % 50`、评分 40。本基线没有大表、历史数据、写流量、距离排序或真实关键词分布。

使用官方 [k6 v1.3.0](https://github.com/grafana/k6/releases/tag/v1.3.0) macOS ARM64 release。归档 SHA-256 为 `eb06b22418e26f7394023e53aaaddf0bec739f669acf718cc9b0e2d7f12bd7be`。`scripts/prepare-native-k6.py` 将二进制准备到临时路径；测量 harness 通过 `K6_BINARY` 使用它，并核对可执行文件哈希。每个用例分别执行 1 和 10 VU，四个组合各重复三次：`repeat → VUs → case` 顺序执行。每组独立 5 秒预热和 20 秒测量，共 12 次预热、12 次测量，不把预热混入测量。每次 gracefulStop=5s、HTTP timeout=5s、无 think time、保留默认连接复用。VU 是持续循环的执行单元，不等于在线用户或固定到达率；测量结束后可完成在途请求。

k6 在宿主机原生运行，通过 harness 新建 Compose 应用随机发布到 localhost 的端口访问应用：固定主机 `127.0.0.1`，JS 仅接收数值 `TARGET_PORT`（1–65535），拒绝 `BASE_URL`。端口来自本轮新建的 Compose 应用，不使用 TLS、鉴权、跨机器网关或外部网络。原生到 `localhost` 发布端口再进入 Docker VM 的链路，与旧 Docker k6 容器共享网络 namespace 的路径不同，结果不可直接当作同一环境、同一路径的可比复测。吞吐量采用 k6 `http_reqs.rate`；P95/P99 采用 k6 `http_req_duration` 毫秒统计，包含发送、等待和接收，不是服务端 dispatch 或 Prometheus histogram 分位数，也不包含连接建立/阻塞时间。每轮至少完成一个请求且所有迭代完整。

## 复现

本次受支持的负载宿主为 macOS ARM64。设置 Java 8、安装 Python 3 和可用的本机 Docker/Compose；准备工具需要 curl/网络，其余阶段使用本地二进制。部署镜像首次拉取也需网络。不使用个人 `.env`，不需要修改已有环境或清空自己的库。

```bash
./mvnw clean package
export K6_BINARY="$(python3 scripts/prepare-native-k6.py)"
python3 -m unittest discover -s scripts -p test_performance_baseline.py
python3 scripts/run-performance-baseline.py
```

下载器核对官方归档 SHA-256 后只提取一个已知成员，不把整个 zip 展开到仓库；harness 另要求可执行文件 SHA-256 为 `40fa9d8cb693a9bb8034810057a6eb3b45318e3258d1a11cdaa95d72f582ccb2`。临时工具不写入 PATH/系统目录、不进入 Git。JS 禁止重定向，目标 host 不可配置；直接独立运行 JS 不会自动创建合成环境，必须由驱动传入本次发现的端口。

驱动复用现有验收的隔离原语，并显式使用 `.env.example` 与测试合成值，建立 UUID 项目、随机 localhost 发布端口、新卷与应用镜像。默认三个恢复 worker 与 INFO 安全请求摘要保持开启；Prometheus 每 5 秒采样。在可信、空闲的本机运行；不要同时启动构建、其他基准测试或改变 Docker/资源配置。驱动无法证明其他用户进程完全无干扰。

`compose.performance.yaml` 仅作为第三层测试 overlay：应用 2 CPU/768 MiB，JVM `-Xms256m -Xmx512m`；MySQL 1 CPU/768 MiB；Redis 0.5 CPU/256 MiB；Prometheus 0.5 CPU/256 MiB。驱动断言四个运行容器的实际限制。原生 k6 进程设 `GOMAXPROCS=1` 和 `GOMEMLIMIT=256MiB`（Go 软内存/GC预算）；这不是 Docker CPU、内存或 PID 硬限制，也不等同于旧 runner 容器的配额。k6 使用私有空配置和最小进程环境，不读取用户配置且禁用遥测。工具进程由 harness 设定超时并清理直接子进程；driver 负责 Compose 项目清理。应用及 MySQL、Redis、Prometheus 的既有配额保持不变。

必须基于已提交的运行时代码构建 jar；驱动拒绝 `src/main`、POM、Dockerfile、普通部署 overlay 的未提交修改。结果记录应用 revision、jar 与负载脚本/驱动/支持脚本/性能 overlay 的 SHA-256、工具和依赖实际版本、k6 release/二进制摘要、硬件与 Docker 配额。jar 哈希用于标识本次实际产物，不保证不同时间重新构建字节一致。

## 发布门槛与原始证据

每轮同时验证 HTTP 200、业务 success、合成返回内容、检查数、零 HTTP/业务错误、requests=iterations、全部 HTTP timing 总量/分项及 tagged series 的有限非负耗时、分位数顺序和平均值的 min/max 边界；k6 阈值也要求所有检查通过与零错误。缺失指标不能解释为 0。预热同样校验；详情请求 ID 仍是 legacy 数字，搜索项 ID 为字符串，两者契约没有被统一修改。

本次结果文件为 k6 原生 `--summary-export` **未经修改的聚合汇总 JSON**，不是逐请求样本、时序轨迹或服务器监控录像。每个正式文件均有单独的 `-warmup.json`；`manifest.json` 保存每轮请求数、checks、速率、客户端耗时、cache 增量和前后采样时间，以及 raw 文件 SHA-256。

只对相同用例/VU 三轮的 **各轮 RPS、各轮 P95、各轮 P99** 分别报告中位数及 min/max 范围。不平均或拼接百分位数，不把三个运行当合并分布，不挑最好一轮，也不把两个不同接口互相比作缓存优化收益。

finally 在超时后清理 k6 直接子进程，并清理唯一 Compose 项目的容器、网络、应用镜像和合成卷，随后断言无残留；基础镜像保留。只有全部 12 轮、窗口检查和清理成功后才发布。私有命令/应用日志留在权限受限临时目录，不进入 Git；公开汇总在发布前拒绝私人路径片段及 `authorization/password/token/phone/session/cookie` 词，不把这个词匹配检查称为通用脱敏器。公开字段另外限定为合成业务结果和安全环境白名单；原始文件哈希必须一致，输出目录不可覆盖。

## 解释边界

- 这是单机资源受限、短时、顺序测量。负载工具、Docker VM、数据库、应用与 collector 竞争同一宿主资源，结果不能迁移为生产容量承诺。
- 搜索预热让 MySQL buffer pool/系统页缓存受益；“MySQL 搜索”不等于冷盘随机读，详情测量则刻意限定为全正缓存命中，不代表真实混合命中率。
- 无开放到达率、长时间稳态、写入/读写竞争、冷缓存 miss/负缓存、超时风暴、限量活动竞争、支付、多副本或跨机链路验收。
- 没有同环境、同请求、同数据的优化前后对照，因此没有提升百分比、瓶颈因果证明、告警阈值或生产 SLO 结论。
- 历史 Docker k6 测量两次均因原始计时负值被拒绝：一次总耗时负值，另一次总耗时为正但 sending 分项为负。待提交结果已整体撤回到私有诊断目录；没有修改负值或放宽门槛，详见阶段记录。改走原生路径不证明 Docker/工具计时异常的根因已解决；原生测量成功后也只说明该次数据通过校验。
- Phase 6A/6B 文档中的“该阶段尚未压测”保留为历史事实；本阶段的小规模读取基线不补足历史迁移、真实短信、进程关键窗口强杀或 HA 的验证缺口。

事实限定的讲述方式见 [面试材料](../interview/README.md)，阶段范围与验证结果见 [Phase 6C 记录](../refactor/18-phase-6c-performance.md)。
