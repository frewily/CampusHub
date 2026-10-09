# Phase 5：可重复的本地部署与图片存储

日期：2026-10-09。范围基线：Phase 4B 最终修复 `4a631b035ad0855c878c8a32bbe3890b4d32fc40`。

本阶段完成实现和下述本机验收；提交后审查与正常推送按阶段流程执行，Phase 6 不在本次范围内。没有引入 ES、专业 MQ、Actuator、指标平台或付费对象存储；尚未进行实际压测。

## 1. 改动范围

- 配置分为 dev/test/prod，默认 dev；test 固定不可用本机端口、禁用 worker，显式 IT 自己拥有连接配置。prod 不回退开发凭据，并在网络客户端创建前校验必填配置、专用 DB 用户、UTC 参数、Redis 端口、绝对非根图片目录；不输出实际值。
- RedisProperties 统一 Lettuce/Redisson 的地址、密码、数据库、username/SSL 配置来源，移除 Redisson 单独读取环境变量的分歧。连接与命令设置有界超时；日志默认 INFO，错误响应不包含异常 message/stack。
- Compose 默认 MySQL/Redis，可选 app profile；host 只绑定 localhost，无固定 container_name，项目级 named volumes。应用非 root，默认容器 UID/GID 为 10001:10001；镜像复用本机 Java 8 构建的 jar，严格限制构建上下文。
- fresh bootstrap 拒绝非空库，依次 schema、V001–V005、合成 seed。没有 DROP 或教学用户/订单/库存。显示宽度/ZEROFILL 被移除；活动起止时间零日期默认变成可空值，仅属于 fresh schema，不是旧库迁移。
- 本地 ImageStorage 适配器替代硬编码 Windows 目录，实际 JPEG/PNG 内容识别、像素/字节预算、解码后重编码、UUID 路径、精确删除、路径与符号链接控制。增加公开图片读取与 nosniff，IO 故障返回 IMAGE_STORAGE_UNAVAILABLE 503；缺文件 part/超大 multipart 返回 typed 400。
- live 与 ready 分离，ready 实际 SELECT 1 + 认证 Redis PING，只返回 UP/DOWN；JRE-only 容器 HTTP 探针不启动第二个 Spring 上下文。增加只读 smoke 与全新合成 Compose 验收脚本。
- 固定 Surefire 3.1.2，补默认测试，重写 README 和部署学习文档；忽略本机环境变体、图片数据和日志。

## 2. 验证方式与实际结果

所有业务验收使用测试拥有的本机进程或独立 Compose 项目，没有连接共享业务库，也没有读取用户真实 `.env`。

### Java 构建和隔离回归

JDK：Amazon Corretto 1.8.0_492。Maven Wrapper：3.9.9。

```bash
./mvnw clean test
./mvnw -Dtest=ShopCacheRedisIT,OrderCancellationRedisIT,OrderStreamRedisIT,FlashSaleRedisScriptIT,ShopCacheMySqlRedisIT,OrderLifecycleMySqlRedisIT,ShopSearchMySqlIT test
./mvnw -DskipTests package
```

- 完整默认套件：229 项，0 失败/错误，4 项手动教学实验按设计跳过。新增 24 项：图片存储 8、生产配置守卫 4、依赖就绪 1、JRE 探针 1、安全/HTTP 合同 10。
- 7 个显式隔离 IT 套件：82 项，0 失败/错误/跳过。本机 MySQL 9.6.0、Redis 8.6.2；重新运行本阶段回归，不沿用旧阶段结果。
- package 成功，验证实际 Boot jar 包含 PropertiesLauncher 与 HealthProbe。首次离线 package 缺少 Maven jar-plugin 缓存，补齐下载后再次离线打包成功；不是业务测试失败。

上述默认 HTTP 测试使用 MockMvc 和 mocked 依赖；不能替代以下实际网络验收。

### 全新 Compose 实际网络验收

```bash
python3 scripts/verify-compose.py
```

Docker Engine 29.6.1 / Compose 5.3.0，desktop-linux 本地 Unix socket；arm64 实际镜像。目标实测 MySQL 8.4.11 / Redis 6.2.24。每次唯一项目、随机 localhost 端口、全新三个数据卷、合成凭据，使用 `.env.example` 和显式覆盖，不读取自己的 `.env`。

10 组检查：

1. fresh 初始化、非 root UID 10001 的打包应用、健康接口与合成门店搜索 smoke。
2. 真实 MySQL + 认证 Redis 的门店详情链。
3. V001–V005 重复执行；对已填充库运行 bootstrap 被拒绝，原 seed 保留。
4. 匿名上传 401，合成 USER 上传有效 PNG，公开读取正确 MIME/nosniff；不信任原文件名与声明 MIME。
5. 重启应用后图片内容保持不变。
6. USER 删除 403、ADMIN 精确删除、越界路径 400、损坏及超大上传 400、删除后读取 404。
7. MySQL 重启后修改过的门店数据仍存在，不重放 seed。
8. Redis 停机时 ready 503、live 200，恢复后 AOF 中合成会话仍存在。
9. 本机 Java 8 运行同一 jar、连接私有 Compose 依赖并通过 smoke。
10. 同一 jar 的 prod 缺配置启动非零退出，守卫错误出现在 Redisson/Hikari 启动之前。

合成 USER/ADMIN 数据库角色及 Redis 会话是验收 fixture，不代表真实短信登录全链路。ready 只检验依赖可达，不保证完整表结构、worker 追平或业务库存状态。

原始命令日志和结果 JSON 留在脚本输出的私有临时目录：目录 0700、日志 0600；不提交机器路径或原始日志。脚本最终只删除自己项目的容器、网络、构建应用镜像与合成卷，基础镜像保留。完整验收需 Java 8、Docker、curl、python3，以及已构建 jar。

### 部署失败调查与修正

- 初次 Compose 失败：只读父目录挂载之下又建立 migration 挂载点。改成独立 schema/seed 单文件挂载与 migration 目录挂载，再验证。
- Redis 启动失败：Alpine BusyBox 不接受 `XXXXXX.conf` 形式的 mktemp 模板，容器内直接复现；改成以 XXXXXX 结尾的模板。以 redis 用户运行，配置文件只读于该用户/管理员权限面，不把密码当 redis-server 参数。
- 应用重启验收失败：脚本保留旧的随机 published port。独立临时容器复现重启前后端口变化，重新发现应用端口后整套通过；不是靠增大等待时间掩盖。
- 提交后清理复查发现：未启用 app profile 的 down 留下了验收 app 容器与上传卷，早期“全部清理”输出不准确。按确切合成项目清理残留，修正为启用 app profile 的 down，并加入清理后项目容器、卷、网络和应用镜像均为空的断言；重新运行完整验收。
- 图片测试定位并修正了先创建目录再检查 symlink、损坏根目录误报 404 两个问题，最终图片存储 8 项全部通过。

修复采用系统化调试：读取错误、复现、追踪边界、最小修改后复验。失败记录保留，不作为通过证据。

## 3. 兼容与部署边界

- 旧 hmdp 数据与旧图片不会自动迁移。旧 SQL 含破坏性 DROP，不再是 README 默认初始化路径；旧库须备份并审查逐版本迁移。fresh init 非事务式，失败可能留部分表。
- 图片根必须由应用/可信管理员独占，不防御同 UID 恶意进程修改父目录的 TOCTOU；没有崩溃原子写/写失败半文件自动清理保证。历史非 UUID 图片需另行转换引用。公开图片读取不适合隐私附件。
- 新卷继承非 root 属主；既有 root 属主图片卷需人工检查。环境文件改 DB 密码不自动更新旧 MySQL 卷中的账号密码。
- Compose 明确 dev，单机本地模板不用于宣称高可用。环境变量对 Docker 管理员可见；Redis 私有配置保留在容器 `/tmp` 直至删除，AOF 与 named volumes 不是备份。
- prod 守卫只做列明的校验，不验密码熵、数据库 TLS、证书、备份恢复、权限全覆盖、HA 或线上安全。
- Java 8 / Boot 2.7 的遗留支持风险仍存在；没有偷偷升级 Java 或依赖平台。
- 部署 smoke 不等于所有旧业务在目标 MySQL/Redis 版本上已完整验收。历史数据迁移、真实短信登录、强杀/failover、性能与生产治理仍待专门验证。

## 4. 下一阶段

当前阶段提交后审查完成再进入 Phase 6。Phase 6 将按计划增加必要的可观测性与可复现性能测量，任何 QPS/P95/P99/命中率都必须来自保存的实测结果。本阶段没有实际压测或虚构业务规模。
