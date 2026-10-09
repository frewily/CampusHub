# 可复现部署：配置、数据与运行边界

这份文档解释 Phase 5 的部署实现怎样把本地开发依赖、Spring 配置、持久化文件和健康探针连起来。这里说明源码契约和设计取舍，实际运行证据单独记录于 [Phase 5 阶段记录](../refactor/15-phase-5-engineering.md)，不提供性能结论。

## Compose 的默认启动范围

`compose.yaml` 定义 MySQL、Redis 和应用三个服务。MySQL 与 Redis 没有 profile，执行普通 Compose 启动时会包含它们；`app` 带有 `profiles: [app]`，只有显式启用 `app` profile（例如 `--profile app`）才会加入。这样可以先启动依赖，再在 IDE 或本机单独运行 Java 应用。启用 app profile 后，应用等待两个依赖通过健康检查才启动。

三个服务的发布端口都绑定在 `127.0.0.1`：默认 MySQL `3307`、Redis `6380`、应用 `8081`，可用相应的 `*_PUBLISHED_PORT` 覆盖。这是本机开发默认值，不应被误解为面向公网的生产网络方案。Compose 中应用连接的是容器 DNS 名 `mysql`、`redis` 和容器端口，而不是宿主机映射端口。

Compose healthcheck 的职责也不同：MySQL 用应用账户执行 `SELECT 1`，Redis 用密码认证后执行 `PING`，应用通过一个不启动 Spring 上下文的轻量 Java HTTP probe 请求 `/health/ready`。服务变为 healthy 只说明对应探针通过，不等于完整业务验收。

## dev、test、prod 与 `.env`

`application.yaml` 提供共同设置，并将未显式指定的 Spring profile 默认设为 `dev`。`application-dev.yaml` 提供本机连接地址和仅供开发的连接默认值；`application-test.yaml` 刻意指向不可达的本机端口，并关闭后台 worker，避免普通离线测试意外连到真实服务；显式集成测试需要自己提供隔离依赖配置。`application-prod.yaml` 不给数据库、Redis 密码或图片目录提供演示默认值，生产环境必须显式注入。

根目录 `.env.example` 是变量清单和示例值，不会被 Spring Boot 自动读取。Compose CLI 会读取其项目 `.env` 文件用于 `${...}` 插值，再把 Compose `environment` 中列出的值传给容器；本机直接运行 Spring 应用时，`.env` 不会因此进入 JVM 环境。Spring 配置通过 `DB_URL`、`DB_USERNAME`、`DB_PASSWORD`、`REDIS_*` 等占位符读取的是进程环境或 Spring 自身配置源。应从受控的本地环境注入这些变量；不要把示例值当作凭据，也不要把真实 `.env` 纳入版本库。

Compose 的 `app` 服务当前固定设置 `SPRING_PROFILES_ACTIVE=dev`，所以即使镜像 Dockerfile 默认 profile 是 `prod`，Compose app 容器仍运行 dev profile。若部署到生产，不能直接把当前 Compose app 配置当成生产配置：需要采用 prod profile、单独注入必需配置，并满足生产守卫。

## UTC 与 prod 启动守卫

Compose 为 MySQL 设置 `--default-time-zone=+00:00`，数据库 JDBC URL 同时包含 `serverTimezone=UTC` 与 `forceConnectionTimeZoneToSession=true`。业务中依赖 UTC 的数据库时间表达式和活动时间解释需要统一时区；只在 JDBC URL 写 `serverTimezone` 不能单独证明 MySQL 会话时区正确。

`ProductionConfiguration` 只在 `prod` profile 生效，并在创建网络客户端之前检查：MySQL URL 必须是 MySQL JDBC URL 且只出现一份所需的 UTC 参数；数据库账户不能是 `root`；数据库密码、Redis 主机/端口/密码必须提供且不能是空值或示例占位值；图片目录必须是绝对、非根目录路径。守卫只验证配置形状与非空性，不能证明凭据有效、账户权限最小、服务可达或部署安全。

## Fresh bootstrap 不是旧库迁移

MySQL 服务把 `schema.sql`、`seed.sql`、V001–V005 迁移文件只读挂入 `/opt/campushub/db`，把 `00-bootstrap.sh` 单独挂入官方初始化目录。MySQL 官方镜像的初始化目录仅在数据目录为空的首次初始化流程中执行。脚本还自行检查 `campushub` 中现有表数，非空时拒绝继续，然后按顺序建 fresh schema、应用五个迁移并载入种子数据。

因此这是一条针对全新 `mysql-data` 卷的 CampusHub 引导路径，不是把旧 `hmdp` 数据自动搬迁、改名或兼容升级的工具。已有非空库不会因重新启动容器而重放这些文件；对旧数据需要另行制定备份、结构差异核对、数据转换、迁移顺序与回滚方案。不要把“初始化脚本可重复挂载”表述成“初始化会在每次启动重跑”。

## 图片本地适配器的契约与限制

业务通过 `ImageStorage` 接口存取图片，当前实现是 `LocalImageStorage`。它把配置目录解析为规范化的本地根目录，博客图片使用受约束的分片路径和 UUID 文件名；读取与删除只接受匹配的路径形式，逐级检查父目录，拒绝符号链接，并且删除单个精确文件而不递归删除目录。这个根目录应由应用和受控管理员独占写入，不应和不受信任的进程或用户共用可写目录。

上传不仅看扩展名或请求 MIME。实现先限制输入字节数，再由 ImageIO 识别格式并解码，只接受 JPEG 和 PNG，检查正宽高及总像素数，然后把解码后的图像重新编码成对应格式。重新编码会丢掉原始元数据和附加在图像数据后的非图像内容；重新编码后的文件仍须在字节上限内。默认单文件上限为 2 MiB、像素上限为 16,000,000，Servlet multipart 请求总上限为 3 MiB；部署配置可以覆盖图片字节和像素阈值，但属性本身也有限制。读取同样有字节上限。

这是单机本地文件适配器：容器重建后数据依赖挂载的 `app-uploads` 卷；多副本分别使用本地卷时，图片可能无法从另一副本读取。共享网络盘也不能自动获得这里假设的独占根目录语义。扩展到多实例时可实现对象存储适配器（例如 S3 兼容服务），并明确对象权限、生命周期、备份与迁移策略。当前文件创建使用 `CREATE_NEW` 后直接写入；代码没有临时文件加原子重命名、fsync 或事务性文件提交协议，因此不能承诺原子写入或断电后的完整性。

## live 与 ready 的有限语义

`GET /health/live` 只返回 `UP`，表达 HTTP 应用进程能处理该请求；它不访问 MySQL、Redis 或图片目录。`GET /health/ready` 执行 MySQL `SELECT 1` 并向 Redis 发 `PING`，两者均成功才返回 200，否则返回 503；错误响应不透露具体依赖或凭据细节。

ready 没有验证数据库业务表、迁移版本、写权限或真实业务事务，也没有检查图片根目录可写、订单 Stream 消费进度、outbox 积压、后台任务完成情况或服务容量。Compose 应用健康检查只请求这个 endpoint，因此它不是端到端验收、监控体系或 SLA。更完整的上线门禁应由单独的集成 smoke test、迁移检查、业务探针和可观测性方案补足。

## Named volumes、权限与安全关闭

MySQL、Redis 和图片分别使用 `mysql-data`、`redis-data`、`app-uploads` named volume。普通停止或 `docker compose down` 会移除容器和网络，但保留这些卷；删除卷会移除数据库、Redis AOF 和上传文件。`down -v` 会把这些持久数据一起删除，不能作为默认清理命令。销毁卷前必须确认目标项目和数据已有可恢复备份；AOF 也不能替代备份。

应用镜像以 UID/GID `10001:10001` 运行，Redis 容器以 `redis` 用户运行。镜像构建时创建并授权 `/app/data/uploads`，但旧卷或已有卷可能带有与当前容器用户不匹配的所有者/权限，导致上传或 Redis 持久化失败。遇到此问题应先确认卷对应的服务、数据重要性和实际 UID/GID，再按备份与维护流程修复所有权；不要不加区分地递归改权限或删除卷。

Redis 配置启用 AOF 并把文件保存在 `/data` 卷。AOF 提供 Redis 持久化机制，不等于经过验证的备份、异机复制或灾难恢复。Compose 将数据库和 Redis 密码注入容器环境；即使没有把密码打印到应用日志，拥有 Docker 管理权限的人仍可检查容器配置/环境。生产环境应控制 Docker 管理权限，并考虑专门的密钥管理与更细粒度凭据轮换方案。Redis 启动命令生成权限受限的临时配置文件，但这并不改变容器环境变量对管理员可见这一事实。

## 可替代的部署选择

- **仅启动依赖**：本机用 Compose 启动 MySQL/Redis，在 IDE 启动 app；需要另行把连接变量提供给本机 JVM。
- **Compose 一起运行应用**：启用 app profile，使用容器网络和持久卷；当前定义的是 dev 配置，适合本地复现，不直接代表生产拓扑。
- **生产运行应用**：应用可部署在 Compose 之外或编排平台中，连接受控的 MySQL/Redis，使用 prod profile 和密钥注入；需要自行落实 TLS/网络边界、备份恢复、迁移发布和运行监控。
- **多实例图片**：将本地适配器替换为对象存储实现；在完成跨适配器接口、历史图片迁移和故障策略前，不应假定本地卷天然共享。
- **数据迁移**：为旧库单独设计迁移与演练，不复用 fresh bootstrap 冒充升级脚本。

## 面试追问

**为什么默认 Compose 不启动 app？**

依赖服务可独立复用，本机 IDE 调试也不必再启动一个容器应用。启用 `app` profile 后才启动应用，并通过健康依赖条件等待 MySQL、Redis 可响应。

**`.env` 为什么没有自动进入 Spring？**

Compose CLI 将 `.env` 用于文件插值；只有显式列入服务 `environment` 的变量才会注入容器。裸 Spring Boot 读取自身配置源，不会因为项目目录存在 `.env` 就自动加载它。

**`ready=UP` 是否代表可以承载业务？**

只代表一次数据库 `SELECT 1` 与 Redis `PING` 成功。表结构、权限、图片写入、消费链路和业务用例仍需独立验证。

**为什么 fresh bootstrap 遇到非空库会停止？**

脚本的前提是空的 CampusHub 数据库。对非空库自动运行初始 schema/迁移可能造成数据损坏；旧版数据迁移必须有单独的兼容分析、备份和转换计划。

**AOF 能否作为备份？**

不能。它是 Redis 的持久化日志机制，仍需独立备份、异地保存、恢复演练和数据丢失目标设计。

**图片重新编码解决了什么，又没有解决什么？**

它以解码后的 JPEG/PNG 像素重新生成文件，限制大小和像素，并去掉原文件元数据/尾随内容；它没有提供对象存储、多副本共享、原子文件发布或备份恢复保证。

**如何安全停止或清理？**

普通 `down` 保留 named volumes；带 `-v` 会删除持久数据，必须明确确认卷归属与备份后才考虑使用。

## 当前证据边界

本说明依据 `compose.yaml`、`Dockerfile`、`.env.example`、Spring 配置、bootstrap、storage 和 health 源码整理。默认测试、隔离 IT、全新 Compose 部署和本机 jar 的实际验收结果见阶段记录；它们不等于旧卷权限已修复、历史数据已迁移、生产高可用已验证或性能指标已测得。按用户要求，提交后审查完成再进入下一阶段；本次不展开 Phase 6。
