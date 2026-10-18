# Phase 7R：签到业务切片

日期：2026-10-18。基线 `fb88a6fadd695534693cc8fa63ae72c2957aa3b6`。对照原始Prompt和既定模块化单体目标，选择小步业务分包，不再扩展审计API或新增产品能力。仅拆现有签到调用链，其他业务仍保留原包结构。

## 契约与依赖

原链路UserController→IUserService/UserServiceImpl→StringRedisTemplate，同时耦合账号Mapper和登录逻辑。新链路engagement.api.CheckInController→application.CheckInService→application.CheckInStore→infrastructure.RedisCheckInStore，config.CheckInConfiguration负责Spring装配与Clock.systemDefaultZone()。应用服务为纯Java，仅接收用户ID并依赖Clock/存储端口，不依赖Spring、Redis、DTO、ThreadLocal、用户Mapper或登录服务。

- 仍是POST `/user/sign` 与GET `/user/sign/count`，handler仍sign/signCount，`@resourceAuthorization.canParticipateAsUser()`不变。有效USER/MERCHANT可用，ADMIN即使兼有USER/MERCHANT也403；匿名、停用和无有效角色401。身份只取认证会话，不接受外部userId代替。
- 成功仍200 `Result.ok()` / `Result.ok(int)`。Redis键仍 `sign:{userId}:yyyyMM`；SETBIT day-1 true；BITFIELD GET unsigned(day) offset0，统计末尾连续1；null/empty/null首项仍按0处理。今天未签返回0，不读未来位，不跨月累计。
- 每次操作只捕获一次日期，生产继续JVM默认时区，不变更成UTC/北京时间。固定Clock仅供可复现组件测试。没有新TTL、SQL、schema/迁移、积分、补签、跨月连续、历史清理或通知。
- 删除旧UserController和用户服务的签到方法，内部Java API归属改变，不承诺未入库的外部Java调用者二进制兼容。HTTP/Redis契约保留。仍复用dto/utils/security旧共享类型，不宣称整个shared或其他模块已迁移。
- 当前51条应用映射、14个Controller；UserController6条，CheckInController2条，管理员评论Controller仍6条。目录根包扫描与机器清单同步，不限制新业务Controller必须位于旧controller包。

## 验证记录

本轮先发现旧临时副本的Git信息与关键源码不完整，保留原项目和残留文件不动；从公开远端核对上述SHA后恢复全新独立副本。旧独立Maven缓存也缺父POM/插件POM，从公共仓库补回原版本依赖，不读取个人Maven配置或升级版本。此为工作环境恢复，不推断残缺的具体外部原因。

- 首轮61项定向检查通过，0失败/错误/跳过；其中新增应用规则10、Redis空返回/旧键适配5、HTTP权限/身份/响应7、模块边界2、目录扫描1。应用层不依赖框架/旧服务，旧账号测试与修改的测试方法均保留。定向统计不与全量相加当覆盖总数；结构重构不是旧业务故障修复，不虚构红证据。
- 最终Java8离线 `clean package` 默认统计520项，0失败/错误、4项原设计跳过。Corretto1.8.0_492、Maven3.9.9、Boot2.7.18、MyBatis-Plus3.4.3不变。中途在端口受限沙箱运行，既有HealthProbe/Observability共6项因Socket Operation not permitted失败；确认是监听权限后在允许localhost端口环境重跑同一套测试通过，未修改断言或过滤失败用例。
- 六套显式IT共139项通过，0失败/错误/跳过：新签到Redis7、原评论MySQL82、投递MySQL24、发布MySQL5、编辑MySQL14、Feed Redis7。本机自建MySQL9.6.0/Redis8.6.2及随机localhost端口，不连接共享服务；默认Surefire不发现 `*IT`，这139项独立于默认520统计。
- 新私有Redis测试验证旧月键导入、unsigned31位和31天连续计数、月首/月末偏移、今天未签/未来位、间断、同日20次设置仍一位且不加TTL、跨年/闰日/跨月隔离、不同用户及重建应用服务读同一Redis数据。固定Clock只证明可复现日期规则，不代表跨时区部署或持久化灾备。

- 本轮最终jar在全新Compose完整50组通过（原48、新2），MySQL8.4.11/Redis6.2.24。新增真实HTTP检查有效USER/MERCHANT、ADMIN混合角色403、匿名/停用/无角色401、query伪造userId无效、响应兼容、同日重复一位、无TTL及用户隔离；预导入原格式月度位图后读取本月天数，保留历史月键，角色实时变化后拒绝且位图不变。HTTP只验证运行时当前日期；月底/跨年/闰日/跨月由固定Clock真实Redis组件IT覆盖，不冒充跨日期部署验收。
- 原评论/回复/审核/审计、动态编辑/撤回、Feed游标/可靠投递、发布编号、图片、安全、依赖重启、主机jar与prod缺配置拒绝全部回归通过。脚本清理本次自有容器、网络、合成卷及项目镜像后，按精确项目标签和镜像名独立读回四类均无残留；IT日志记录的8个私有端口均无监听。只读复核原教学目录HEAD及原未跟踪文件不变。
- 26个阶段文件的选定高可信私钥/AWS/GitHub token/个人绝对路径模式无命中，不宣称完整安全审计。8份相关Markdown共309个相对文件链接存在性、脚本AST/机器JSON、`git diff --check`通过；目录仍51条且只有两个签到handler归属类型变动，其余method/path/guard不变。最终jar内8个变动生产类与当前编译文件逐字节一致。未读取个人.env/凭据，未操作共享DB/Redis或生产部署。

以上是选定本机验证，不把mock、静态目录或组件IT当生产/容量/浏览器或个人独立掌握证明。

## 部署与回退

本轮不新增V015，保留V001–V014。已有库仍须满足此前阶段迁移与应用兼容要求；不操作共享库、不做生产部署。原Redis月键可直接读取，不需要导入/搬迁/删除。仅从7R回退到7Q不需要数据回退；7K及之后已有withdrawn兼容限制仍适用，不能据此回退任意更旧版本。

JVM默认时区延续旧行为，多实例需自行统一时区，本轮不提供跨时区一致性、持久化灾备、生产容量或性能提升结论。完成实现→验证→scoped commit→新未参与实现的Luna只读独立审查实际SHA→普通push→fetch/readback，停在本阶段；最终状态以交付对话为准。个人掌握仍需独立确认。
