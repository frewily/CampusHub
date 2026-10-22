# Phase 7U：关注写入业务切片

日期：2026-10-22。基线 `6a63085382d9736bcf2e86948b4674267d8679b5`。恢复上次被中断的未提交切片后，继续既定模块化单体分包；原教学目录和未跟踪文件保留。

## 范围与兼容边界

PUT `/follow/{id}/{isFollow}`迁到social.api.FollowController→FollowCommandService→FollowCommandRepository/FollowProjectionWriter→MyBatisFollowCommandRepository/RedisFollowProjectionWriter，由config装配生产Clock.systemUTC。应用服务通过参数接收actor，不依赖Spring、Redis、MyBatis、ThreadLocal或通用IService，仍复用旧BusinessException/ErrorCode。Clock.millis与旧System.currentTimeMillis同为系统毫秒，不改变时区或score单位。

- URL、handler、角色USER/MERCHANT/ADMIN、正Java Long与Boolean转换、有效会话身份及Result.ok无data不变；query userId不可替代actor。匿名/失效/停用/无有效角色401，非法参数400，自关注与自取关均409。不给目标用户增加存在/状态检查。
- 关注仍先COUNT事实，已存在则不INSERT；否则INSERT，DuplicateKeyException按原规则视成功，其他SQL异常传出。成功才ZADD `follows:{actor}`，member为目标ID十进制字符串，score为调用时系统毫秒；重复关注也刷新分数，不主动改TTL。不新增锁、事务、回填、重建或并发线性化保证。
- 取关仍按actor/target DELETE，只有删除成功才ZREM。没有事实但有陈旧投影时，重复取关成功但不清理。MySQL提交后Redis失败仍是通用500，SQL不回滚；重复关注可刷新投影，重复取关不承诺修复。未静默吞错、自动补偿或加强跨库一致性。
- 旧IFollowService移除公开follow方法；FollowServiceImpl不再注入Redis，仅保留Blog发布所需继承CRUD桥接。BlogServiceImpl源码、Feed收件人快照/投递链、查询用例生产源码均不改。Follow实体/Mapper仍在旧包；这不是整个social/identity迁移。
- 仍51条映射/15个Controller，PUT的简单类名和handler不变，routes.json保持原样；旧controller包FollowController删除，clean构建避免残留扫描冲突。V001–V014不变，没有新schema或数据迁移。

## 验证记录

- 53项定向检查通过，0失败/错误/跳过：写编排11、读编排8、适配器11、模块边界2、读写HTTP9、接口目录8、安全策略/会话过滤4。旧写入两个测试方法名随用例迁移，保留不INSERT仍ZADD和自关注前不访问存储的断言语义；查询参数400、查询通用500等原读取覆盖保留，未用写入测试替代。
- 最终Java8离线clean package：584项，0失败/错误、4项原设计跳过，相对7T净增18。Corretto1.8.0_492、Maven3.9.9及既有依赖版本不变。首轮受限环境中584项有6个错误，均在HealthProbe/Observability创建本机监听时出现SocketException Operation not permitted；沿调用栈核对未变测试后，仅改变执行权限作对照，原6项全部通过，再完整clean package通过。不改生产代码/测试断言来绕过权限问题。
- 八套显式组件IT共161项通过，0失败/错误/跳过：关注读写14、分类8、签到7、评论82、投递24、发布5、编辑14、Feed Redis7；本机自建MySQL9.6.0/Redis8.6.2、随机localhost端口、合成表/键。当前关系夹具加唯一键以验证真实重复INSERT恢复；覆盖成功删除、SQL提交后Redis WRONGTYPE失败、重复取关不修复陈旧投影，以及固定score更新不主动改变TTL。默认Surefire不发现这些IT，161与584独立统计，定向检查与全量不累加为覆盖总数。
- 全新Compose真实HTTP完整56组通过（原54、新2），MySQL8.4.11/Redis6.2.24。新增三角色写入、会话actor不可被query替代、匿名/停用/无角色401、正Long/Boolean400、自关注/自取关409、没有目标用户行的大数字目标可关注、重复关注刷新score且不改TTL、重复取关保留陈旧投影。真实WRONGTYPE验证新增/删除事实已提交但投影失败500，重复关注可再刷新、重复取关不会自动修复；临时关系表故障期间投影不变，finally恢复后写链可用。只操纵本次自有合成环境，不作并发线性化或生产可靠性承诺。
- 原查询、分类、签到、评论/回复/审核/审计、动态发布/编辑/撤回、Feed恢复、发布编号、图片、安全、依赖重启、主机jar和prod缺配置拒绝均保留回归。HTTP使用并行测试用构建；最终clean package后，jar内全部207个应用class/资源条目的有序内容指纹与HTTP验收包一致（SHA-256 `3a58c20cd66b3d802b3ff01c3dd661509fe199221a419224950a427a81b0e0f9`），不将zip容器时间戳当作应用内容变化。
- 精确Docker项目的容器、网络、卷和应用镜像清理后独立读回均空；本轮日志中14个私有IT端口均无监听。BlogServiceImpl、查询用例生产源码、routes.json与V001–V014同基线无差异；原教学目录HEAD与未跟踪文件未改。相关Markdown文件链接存在性、Python AST、机器JSON、git diff --check和变更路径的选定高可信凭据/私钥/个人绝对路径模式检查通过；不是完整安全审计。

## 部署与回退

只使用独立开发副本、临时无凭据Maven settings/依赖缓存、自建localhost MySQL/Redis和全新Compose合成环境；不读个人.env/凭据，不连共享DB/Redis，不生产部署。阶段文档之外的临时日志、依赖、配置不提交。

需clean构建完整jar，替换7T不新增DDL或Redis格式变化；满足已有迁移前提时回退7T不需数据回退，不代表可任意回退到不识别撤回标记等更早版本。旧内部Java方法移除不承诺外部二进制兼容。

按实现→验证→scoped commit→新未参与实现的Luna独立只读审查实际SHA→普通push→fetch/readback后停止。最终同步与审查状态以交付对话为准。本机选定验证不代表生产、容量、HA、浏览器或个人能力认证；个人掌握仍需独立确认。
