# CampusHub

CampusHub 是从“黑马点评”教学项目渐进演进的校园生活与周边商户服务后端，目标是让设计、实现和验证都能被解释和复现，而不是隐藏来源或堆叠中间件。

目前完成 Phase 5、Phase 6A/6B/6C 的选定本机范围：认证与权限、请求/响应边界、限量活动准入与可靠消费、订单查询/取消、门店缓存治理、MySQL 搜索、可重复本地部署，以及请求编号、可观测性基线、固定标签业务诊断、核心 HTTP 故障补验和可追溯的只读性能基线。Phase 6D 补齐原文要求的三份面试材料并盘点交付边界；不代表原始业务目标全部落地、生产容量、限量活动压测或性能优化提升。

## 架构与业务

```text
HTTP → Spring Security（Redis Token + 数据库账号/角色）→ Controller → Service
                                                               ├→ MyBatis → MySQL
                                                               ├→ Redis（会话/缓存/Feed/Lua）
                                                               └→ ImageStorage → 本地目录
限量活动：Lua 准入 → Redis Stream → 幂等订单消费 → MySQL
取消/商户更新：MySQL 事务 + outbox → 提交后 Redis 操作 → 有界恢复 worker
```

- USER / MERCHANT / ADMIN 权限与商户资源归属检查；采用可失效的 Redis Token，不是 JWT。
- 门店、校园动态、关注、Feed、签到和优惠活动沿用合理的遗留模块；不是所有遗留接口都已完成 DTO 改造。
- 库存、资格、时间窗口、一人一单与请求重放由 Redis Lua 校验。`ACCEPTED` 只代表进入 Stream，不代表订单落库或支付。
- 消费者支持 pending 恢复、有限重试、失败归档和运维前向重放；数据库有唯一约束。保留 Redis Stream，不引入专业 MQ，见 [ADR 0001](docs/adr/0001-order-message-broker.md)。
- 本人已落库未支付订单支持查询/取消，取消与 outbox 同事务；Redis 补偿独立恢复。不包含支付、退款或自动超时取消。
- 门店详情使用 Cache Aside、负缓存、TTL 抖动、token 互斥、epoch 发布栅栏和事务失效 outbox；不宣称即时强一致。
- 商户搜索支持名称子串、类别、价格、评分、距离及稳定分页；直接读取 MySQL，暂不引入 ES，见 [ADR 0002](docs/adr/0002-shop-search-engine.md)。

技术栈：Java 8、Spring Boot 2.7.18、Spring Security、MyBatis-Plus、MySQL、Redis/Lettuce、Redisson、Lua、Docker Compose、Actuator、Micrometer、Prometheus。Java 8 / Boot 2.7 是现阶段保留的遗留基线，不等于已满足生产支持与安全维护要求。

## 环境要求

- **JDK 8**，设置 `JAVA_HOME` 并确保 `java -version` 使用同一版本。构建会拒绝其他 JDK。
- Docker Engine / Docker Desktop 与支持 profiles、`up --wait` 的 Compose v2 或更新版本。
- smoke 脚本需要 `curl` 和 `python3`；完整部署验收另需本机 Java 8。
- 首次构建/拉取需要网络；Maven Wrapper 首次下载 Maven 3.9.9。

## 从干净仓库启动

```bash
git clone https://github.com/frewily/CampusHub.git
cd CampusHub
cp .env.example .env
```

编辑自己的 `.env`：替换 `DB_PASSWORD`、`MYSQL_ROOT_PASSWORD`、`REDIS_PASSWORD` 的示例值；数据库使用独立 `campushub` 用户。不要提交真实凭据。Redis 本地脚本只接受 `[A-Za-z0-9._-]`；环境文件同时用于 shell，请保持正确引用，不写可执行表达式。

`.env` 由 Compose 使用，**Spring Boot 不自动加载它**。默认端口仅绑定 localhost：MySQL `3307`、Redis `6380`、应用 `8081`。端口被占用时修改相应 published-port 配置；本机应用同时调整 `DB_URL`、`REDIS_PORT`、可选 `SERVER_PORT`，smoke 传入实际地址。

先构建，默认测试不需要真实数据库：

```bash
./mvnw clean package
```

### 方式一：依赖在容器，本机运行应用

```bash
docker compose up -d --wait
set -a
source .env
set +a
java -jar target/campushub-0.0.1-SNAPSHOT.jar
```

应用启动后，在另一个终端进入项目目录：

```bash
./scripts/smoke-test.sh http://127.0.0.1:8081
```

### 方式二：依赖和应用都在容器

在未运行本机应用时执行：

```bash
docker compose --profile app up -d --build --wait
./scripts/smoke-test.sh http://127.0.0.1:8081
```

Dockerfile 复用刚构建的 jar，不在镜像里下载 Maven；上下文只包含 Dockerfile 和目标 jar。应用以 UID/GID `10001:10001` 运行，Compose 显式使用 dev profile，内部连接 MySQL/Redis 服务名。直接运行该镜像默认使用 prod，需要显式提供配置。

smoke 检查 live、ready 和合成门店搜索，成功输出 `PASS`；它只读，不创建账号或活动。种子图标/图片是 URL 占位，不附带图片资产；本仓库暂无完成验收的 UI 截图，不提供虚构截图。

### 初始化和关闭

首次使用全新 MySQL 卷时，官方镜像运行 `deploy/mysql/init/00-bootstrap.sh`：

```text
空库检查 → schema.sql → V001…V005 → seed.sql（合成类别与一间门店）
```

没有教学个人数据、默认登录用户或活动库存。新库保留业务列含义，去掉显示宽度/ZEROFILL；活动起止时间的旧零日期默认改为可空值，仅适用于 fresh schema。**这不是已有 hmdp 库的迁移/重建方案。** 不要对有数据的库导入旧 `hmdp.sql`（含 DROP）。现有库必须先备份，再按之前阶段文档审查并应用 V001–V005；不会自动修复脏数据或同名错误索引。初始化途中失败可能留下部分表，需要检查，不要反复强行导入。

日常停止容器并保留数据：

```bash
docker compose --profile app down
```

MySQL、Redis AOF、容器上传图片均使用项目级 named volumes。不要把删除卷作为日常关闭步骤；换项目名会得到另一套卷，不会自动迁移旧卷。已有 root 属主的上传卷不会被新镜像自动修复，接入前需检查权限。修改凭据也不会自动修改旧 MySQL 卷中的账号密码。

Compose 是单机本地开发模板，不是生产高可用部署。AOF/数据卷不是备份；Docker 管理员可查看容器环境变量，Redis 密码配置文件留在容器私有 `/tmp` 中直到容器删除。未验收公网 TLS、备份恢复、HA、容量、灾备或生产安全治理。

## 配置、健康和图片

配置分为 `dev`（默认，本机连接/示例值）、`test`（不可用本机端口防误连、关闭 worker）和 `prod`（连接/凭据/目录无开发默认值）。生产模式要求 `DB_URL`、`DB_USERNAME`、`DB_PASSWORD`、`REDIS_HOST`、`REDIS_PORT`、`REDIS_PASSWORD`、绝对非根目录的 `IMAGE_STORAGE_DIR`。启动守卫拒绝缺失/示例值和 root 数据库用户；不代表密码强度、TLS 或完整生产安全校验。

JDBC URL 必须保留 `serverTimezone=UTC&forceConnectionTimeZoneToSession=true`，保持数据库会话和 outbox 时间基准一致。Lettuce 与 Redisson 共用 Spring Redis 配置。三个 worker 开关、claim idle 和重试预算见 `.env.example`；默认值未进行性能调优。claim idle 应大于正常事务耗时。

| 接口 | 含义 |
| --- | --- |
| `GET /health/live` | HTTP 应用仍可响应，200 `{"status":"UP"}` |
| `GET /health/ready` | 实际 DB `SELECT 1` 与认证 Redis `PING`，成功 200，否则 503；不暴露组件/凭据 |
| `POST /upload/blog` | USER/MERCHANT/ADMIN，multipart `file`；返回受控 `/blogs/.../UUID.jpg或png` |
| `GET /imgs/blogs/{first}/{second}/{filename}` | 公开读取受控图片，图片 MIME + `nosniff` |
| `GET /upload/blog/delete?name=...` | 保留 legacy 路由，仅 ADMIN；精确删除，不递归 |

ready 只代表依赖可达，不验证完整 schema、活动可购买、worker 追平或图片存储可写。Actuator 管理面与它分开，见下一节。

图片通过 `ImageStorage` 隔离，本阶段只实现本地适配器。按内容识别 JPEG/PNG，解码再编码；文件/输出最多 2 MiB、请求最多 3 MiB、最多 1600 万像素，拒绝 SVG/损坏输入、路径遍历和符号链接。文件名/MIME 不作为可信依据。根目录必须由应用/可信管理员独占；不防御同 UID 恶意进程改父目录，不提供崩溃原子写或自动孤儿文件治理。读取公开，不可存放私密附件。

历史图片不自动搬迁，非 UUID 或不在受控分片路径的旧资产不能直接经新读取接口访问，必须另行审查转换与引用迁移。单机目录不支持多实例共享、CDN 或对象存储；不引入付费服务。

## 本地可观测性（Phase 6A/6B）

管理监听器默认 `127.0.0.1:8082`，仅允许 GET `/actuator/health`、`/actuator/prometheus`；其他 endpoints 默认禁用。业务 8081 不提供匿名 Actuator 指标。启动守卫拒绝非 loopback 地址、相同非零业务/管理端口与不合法端口；本机可用 `MANAGEMENT_PORT` 选择另一个独立端口。管理 health 复用现有 ready，只返回 UP/DOWN。

本机运行 jar 时可以只读采集：

```bash
curl --fail http://127.0.0.1:8082/actuator/prometheus
```

容器模式不发布管理端口。显式增加本地 Prometheus collector：

```bash
docker compose -f compose.yaml -f compose.monitoring.yaml --profile app up -d --build --wait
```

打开 [本地 Prometheus](http://127.0.0.1:9090)，查询 `up{job="campushub"}`；初次启动需等待采集。app 与 collector 共用网络 namespace，collector 访问 app loopback，UI 9090 只发布到宿主 localhost。`--wait` 不替代 Prometheus 查询验收；管理端口和 UI 都没有额外用户认证，只适用于可信本机/collector，不能直接作为公网生产方案。变更或重建应用时应一起重新建立 collector。

日常停止并保留所有数据卷时必须包含同一个 overlay：

```bash
docker compose -f compose.yaml -f compose.monitoring.yaml --profile app down
```

收集 HTTP server 请求计数/耗时直方图、JVM、HikariCP；路由使用模板标签，uri 上限 100，非标准 method 归 UNKNOWN。当前禁用未使用的出站 HTTP client 指标，以免字面地址/查询产生不安全标签。6B 增加缓存、订单消费、缓存失效/取消 outbox 的 33 条固定 outcome 计数；scrape 只读内存，不查依赖。计数重启清零，不等于唯一订单、lag/backlog 或生产成功率；缓存首次命中率可以按窗口计算，但没有真实业务比例结论。Prometheus 24h/256MB retention 不是整个目录硬配额或备份；没有 Grafana。

每个首次处理的业务请求返回服务端生成的 `X-Request-Id`，客户端输入不会复用；MDC 结束后清理/恢复。新 INFO 摘要只包含安全 method、模板 route、观察到的 status、dispatch 毫秒耗时与 dispatchFailed，不输出头、查询、body、token 或用户 ID。不是分布式 trace，也不自动传播异步线程；摘要 status/耗时不保证等于异常或异步请求的最终状态。

```bash
python3 scripts/verify-observability.py
```

此脚本需要已构建 jar、Docker、curl 和 python3，使用全新合成环境，检查真正进入 Prometheus 的样本并验证精确清理。指标查询方法和边界见 [学习笔记](docs/learning/observability-baseline.md)；运行证据见 [阶段记录](docs/refactor/16-phase-6a-observability.md)。采集成功不等于业务健康，histogram buckets 也不是压测结果。

业务质量与诊断验收（同样需要先构建 Java 8 jar）：

```bash
python3 scripts/verify-business-diagnostics.py
```

使用自己的合成验证码、会话、数据和临时项目验证真实登录/权限、Feed、缓存失效故障、订单重放/归属、DB 确认失败后的取消幂等和消费 pending 恢复，并查询实际 Prometheus。只在合成库注入 trigger/加速租约过期；优雅重启不是强杀、HA 或压测。固定结果计数的含义见 [业务诊断笔记](docs/learning/business-diagnostics.md)。

## 本机性能基线（Phase 6C）

原生 macOS ARM64 k6 1.3.0 测量合成正缓存详情与 MySQL 搜索，1/10 VU、每组合三轮、独立 5s 预热与 20s 测量；保留 24 份未修改的聚合汇总及环境/参数/哈希记录。只报告逐轮 RPS/P95/P99 的中位数和范围，不拼接百分位数、不当生产容量或优化前后对照。复现入口、具体数字和计时异常的历史拒绝记录见 [性能报告](docs/performance/README.md)；事实限定的讲述见 [面试材料](docs/interview/README.md)。临时工具不全局安装、不进入 Git。

限量活动另按 [有限批次方案](docs/performance/flash-sale-plan.md) 推进：Phase 6E1 只交付负载脚本与离线门禁，正式运行/结果报告留给 6E2，**尚未进行限量活动实际压测**。20/200 合成账号、各半库存、每人两请求的计划数字不是实测并发容量；`--inspect-only` 与 Node VM stub 也不是抢购验收或压测结果。

## API 与设计文档

- [领域模型](docs/domain-model.md)、[迁移计划](docs/refactor/02-migration-plan.md)、[遗留兼容说明](docs/learning/legacy-compatibility.md)。
- [请求/响应契约](docs/refactor/09-phase-2d-api-models.md)、[权限](docs/refactor/08-phase-2c-authorization.md)。
- [活动准入](docs/refactor/10-phase-3a-flash-sale-admission.md)、[消费恢复](docs/refactor/11-phase-3b-reliable-order-consumption.md)、[订单取消](docs/refactor/12-phase-3c-order-lifecycle.md)。
- [缓存治理](docs/refactor/13-phase-4a-shop-cache-governance.md)、[搜索契约](docs/refactor/14-phase-4b-shop-search.md)。
- [Phase 5 验证记录](docs/refactor/15-phase-5-engineering.md)、[可重复部署学习笔记](docs/learning/reproducible-deployment.md)。
- [Phase 6A 验证记录](docs/refactor/16-phase-6a-observability.md)、[可观测性学习笔记](docs/learning/observability-baseline.md)。
- [Phase 6B 验证记录](docs/refactor/17-phase-6b-business-diagnostics.md)、[质量覆盖矩阵](docs/refactor/phase-6b-quality-matrix.md)、[业务诊断笔记](docs/learning/business-diagnostics.md)。
- [Phase 6C 验证记录](docs/refactor/18-phase-6c-performance.md)、[性能报告与原始证据](docs/performance/README.md)、[事实限定的面试材料](docs/interview/README.md)。
- [项目故事](docs/interview/project-story.md)、[面试追问](docs/interview/questions.md)、[事实限定的简历要点](docs/interview/resume-points.md)。
- [Phase 6D 交付盘点与剩余范围](docs/refactor/19-phase-6d-delivery.md)：当前仅有重点 API 契约，尚无统一穷尽的接口目录；完整商户/管理后台、评论评价、通知及业务模块分包未交付。原始要求的抢购实际压测仍待单独阶段，不能用 6C 读取结果替代。

`GET /shop/search` 匿名可访问，支持 `keyword,typeId,minPrice,maxPrice,minScore,x,y,radiusMeters,sort,page,size`。详细取值/单位以搜索契约为准；`data` 为 `items,total,page,size,sort,hasNext`，ID 为字符串。HTTP routes、表名和大部分 Redis key 保留；门店详情缓存已切换 `cache:shop:v2:`，不支持新旧缓存写入程序混跑的一致性保证。

## 测试与证据边界

```bash
./mvnw clean test
./mvnw -Dtest=ShopCacheRedisIT,OrderCancellationRedisIT,OrderStreamRedisIT,FlashSaleRedisScriptIT,ShopCacheMySqlRedisIT,OrderLifecycleMySqlRedisIT,ShopSearchMySqlIT test
```

Surefire 3.1.2 已固定在 POM，无需版本覆盖。默认套件不需要数据库/Redis，4 项教学手动实验按设计跳过；显式 IT 自建进程和合成数据，需要 `mysqld`、`redis-server` 在 PATH（或 `MYSQLD_SERVER_BINARY` / `REDIS_SERVER_BINARY`）。不要在共享库运行教学手动实验。

完整部署验收会建立唯一项目、随机 localhost 端口、全新卷和合成凭据，不读取自己的 `.env`，结束只移除该项目的测试容器、网络、应用镜像和合成卷：

```bash
./mvnw package
python3 scripts/verify-compose.py
```

原始日志保留于脚本输出的私有临时目录，不加入 Git。随机端口可能在容器重启后变化，脚本会重新发现。

2026-10-09 的 Phase 5 证据：229 项默认测试（0 失败/错误，4 设计跳过），82 项显式隔离 IT（0 失败/错误），Compose 10 组实际检查，包含 HTTP、图片持久化/权限、依赖停机、fresh 初始化与重复迁移、本机 jar 路径和 prod 缺配置拒绝启动。Compose 实测 MySQL 8.4.11 / Redis 6.2.24，旧隔离 IT 为本机 MySQL 9.6.0 / Redis 8.6.2。

以上是 Phase 5 当时的证据边界：部署角色/会话由合成 fixture 创建，不代表真实登录短信链路，当时尚未进行实际压测。后续 6B 补合成登录/故障验收，6C 补选定只读小基线；仍不等于历史迁移、进程关键窗口强杀/failover、生产性能或全部旧业务的目标版本兼容已验收。

Phase 6A 阶段回归：244 项默认测试（0 失败/错误，4 设计跳过）、82 项隔离 IT（0 失败/错误）、10 组部署回归、5 组实际 Prometheus 3.5.0 采集检查及清理断言通过。默认测试中的新双监听器 HTTP 合同使用 mock 依赖；真实 Compose 验收另用合成 DB/Redis。

Phase 6B 本轮：46 项定向、255 项默认（4 设计跳过）、82 项隔离 IT、8 组目标版本真实业务/故障/诊断验收、10 组部署回归和 5 组管理面采集回归通过。目标流程使用 MySQL 8.4.11 / Redis 6.2.24 / Prometheus 3.5.0；验证码读取只发生在测试自己的 Redis，不是实际短信验收。不同证据层级不能互相替代；未验证关键窗口强杀、多副本、任意网络分区、HA、历史迁移或所有旧业务兼容。

Phase 6C 本轮：Java 8 默认回归 255 项（4 设计跳过）、23 项离线性能/原生工具校验、原生脚本解析及 9 组非法参数拒绝检查通过；12 次正式读取测量和 12 次独立预热通过，共 689737 次正式请求，HTTP/业务错误为 0。原始文件、全部计时分项、缓存窗口、统计与哈希独立复核通过，测试项目已精确清理。没有将上一阶段的 82 项隔离 IT 或 8/10/5 组验收当成本轮重跑；没有限量活动、写入竞争、生产容量、SLO 或性能提升百分比结论。
