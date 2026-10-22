# Phase 7V：发布关注者读取端口

日期：2026-10-22。基线 `6904a30f5614fcf7217c3d850c50daafd8c317f1`，远端核对后继续既定模块化单体方向，不新增产品能力。

## 范围与契约

BlogServiceImpl.saveBlog在原事务/原调用位置，通过social.application.FollowerReader取得收件人ID；social.infrastructure.MyBatisFollowerReader通过原FollowMapper.selectList(QueryWrapper.eq(follow_user_id,authorId))逐行映射user_id。无新增事务注解/传播、排序、去重、分页、用户join/状态过滤、Redis查询或投影修复。Mapper返回列表的原顺序/重复/null user_id保留，不承诺数据库顺序；既有投递服务仍校验ID并去重。

旧IFollowService/FollowServiceImpl无剩余生产调用方后删除，测试夹具迁为专用端口；真实投递IT接真实MyBatis适配器。Follow实体/Mapper保留旧包；关注公开读写生产链不变。这不是整个social、content或Feed迁移。

发布仍先写动态、按作者查询收件人、写同事务投递意图；关系SQL异常沿原DataAccessException映射通用500并回滚。Redis投递失败、固定分数、后台恢复和发布编号重放规则均由原服务处理，不在本阶段增加补偿/投递保证。51条映射/15个Controller、routes.json与V001–V014不变，公开身份权限、参数、响应不变。

## 验证记录

- 43项定向检查通过，0失败/错误/跳过：读取适配器4、发布8、编辑6、撤回6、模块边界2、读写HTTP9、接口目录8。迁移原测试夹具/反射字段而非删除旧业务断言；专用接口mock不冒充真实关系SQL，投递MySQL IT仍接真实Mapper适配器。
- 最终Java8离线clean package共588项，0失败/错误、4项原设计跳过；比7U净增4。Corretto1.8.0_492、Maven3.9.9、既有依赖不变，最终Surefire XML统计另行读回一致。
- 八套显式IT共163项，0失败/错误/跳过：关注读写/关注者16、分类8、签到7、评论82、投递24、发布5、编辑14、Feed Redis7。本机自建MySQL9.6.0/Redis8.6.2、随机localhost端口与合成数据；新组件场景验证作者隔离、空列表、缺失/停用用户仍返回ID、Redis WRONGTYPE不影响读取、SQL故障传出且关系/投影不变、恢复可读。原24项投递IT验证真实适配器下的发布事务和故障规则。163项不在默认Surefire发现范围，定向/默认/IT统计不叠加冒充覆盖总数。
- 全新Compose完整58组通过（原56、新2），MySQL8.4.11/Redis6.2.24。新增真实HTTP发布：关系选中正常/停用/缺失资料的关注者，不混其他作者，故障Redis关注投影不被读/修；用自有Feed WRONGTYPE保留待投递意图检查完整收件人及统一固定score，不与后台ACK竞争。关系表临时故障500时新动态回滚且无孤立意图，finally恢复后可再次发布；旧发布编号、恢复、评论/审核/游标、图片、安全、重启、主机jar和prod缺配置拒绝等全量验收保留。
- 首轮定向43项有1项失败：mock Mapper未渲染SQL时参数Map仍为空，原适配器测试先getSqlSegment再看参数。仅修正新测试观察顺序并加强完整SQL条件断言，4项适配器对照及43项定向通过。首轮Compose中新增发布场景在旧校园动态“初始零动态”检查前创建了数据；根因确认后将新场景移到原发布场景之后，保留旧空库/权限/回滚断言，再全新项目完整58组通过。两处均不改生产规则、跳过原场景或降低断言。
- HTTP使用并行验证构建；最终clean package的207个应用class/资源条目与HTTP验收包有序内容指纹完全一致（SHA-256 `f2d86afa0e408aa3640d793c0d0594224cc5f13d41b92e6aaf9f0150d0ae7fe7`），并与target/classes逐文件一致；两个旧类型在jar中不存在。两次精确Docker测试项目清理后容器、网络、卷和应用镜像独立读回均空，本轮14个记录私有IT端口均无监听。
- routes.json、V001–V014、关注公开读写及Feed投递/发布编号服务生产源码同基线无差异；BlogServiceImpl仅改相关import/字段和原位置的收件人读取。原教学目录HEAD及未跟踪文件保留。7份相关Markdown的325个相对文件链接、Python AST、机器JSON、git diff --check和变更路径选定私钥/凭据/个人绝对路径模式检查通过；不是完整安全审计。

## 部署与回退

仅用独立开发副本、临时无凭据Maven配置/缓存、自建localhost MySQL/Redis进程及全新Compose合成环境，不读个人.env/凭据、不连共享服务或生产部署。原教学目录及未跟踪文件保留，临时日志/依赖不提交。

必须clean构建完整jar，避免删除旧Bean后输出目录残留类；移除旧内部Java类型不承诺外部二进制兼容。不新增DDL/数据格式，满足既有迁移前提时回退7U无需数据回退，不代表可任意回退更早版本。

按实现→验证→scoped commit→新未参与实现Luna独立只读审查实际SHA→普通push→fetch/readback后停止，最终状态在交付对话报告。选定本机证据不代表生产、容量、HA、浏览器或个人掌握认证；个人掌握仍需独立确认。
