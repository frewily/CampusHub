# Phase 7T：关注查询业务切片

日期：2026-10-20。基线 `b4c4dd14f88c32d0935b2f3fa230dde077527c06`。既定模块化单体方向的小步分包，仅迁移关注的两个读取用例；不新增产品能力。

## 契约与范围

原FollowController/IFollowService/FollowServiceImpl中的isFollow/common，迁为social.api.FollowQueryController→application.FollowQueryService→FollowLookupRepository/FollowProjectionReader/FollowUserReader→MyBatisFollowLookupRepository/RedisFollowProjectionReader/LegacyFollowUserReader，config负责装配。应用编排不依赖Spring、MyBatis、Hutool、ThreadLocal或旧通用服务，仍复用UserDTO；用户资料适配器保留IUserService桥接。

- 仍GET `/follow/or/not/{id}`、GET `/follow/common/{id}`，handler仍isFollow/common；正Java Long参数、当前有效会话、原Boolean/List<UserDTO>/Result形状不变，ID仍JSON数字。有效USER/MERCHANT/ADMIN均可读，无额外方法角色限制；匿名/失效/停用/无有效角色401，非法参数400，自查允许。
- 是否关注只对原tb_follow按actor/target参数绑定COUNT>0，不额外查目标账号或根据Redis判断。共同关注仍读两个 `follows:{id}` ZSet的range(0,-1)，处理null/empty/无交集为空，HashSet交集转Long后按旧IUserService.listByIds与BeanUtil映射UserDTO；不排序、不分页，不用MySQL关系交集替代投影。缺失用户自然省略，展示对象停用不增加新过滤。
- 坏交集member、WRONGTYPE、SQL/资料异常维持原通用500；不吞错、回填、重建或写入修复。查询不改关注事实、ZSet成员/score/TTL；既有认证过滤器的会话续期不变，不宣称整个HTTP无写。
- 原PUT关注/取关方法与角色声明不改；BlogServiceImpl/Feed发布调用链不改，旧IFollowService仍提供写入和通用关系查询。两个读取方法从旧类型移除，旧内部Java API二进制兼容不承诺；原共同关注ZSet回归断言迁入新适配器测试，原写入回归保留。
- 当前51条应用映射/15个Controller，旧FollowController1条PUT，新FollowQueryController2条GET，目录仅改两个归属。无新SQL/schema/迁移、TTL、排序承诺、通知、全量social/identity迁移或跨MySQL/Redis快照保证。

## 验证记录

- 52项定向检查通过，0失败/错误/跳过：新应用编排8、适配器4（其中1为旧ZSet用例迁移）、模块边界2、HTTP7，外加旧安全、写入与目录检查。原测试方法名均保留或随用例迁移，关注写方法与基线逐字节一致，Blog发布源码不改；Feed投递IT只移除已不再存在的读取资料字段注入，未放宽断言。定向与全量不累加当覆盖总数。
- 最终Java8离线 `clean package` 默认统计566项，0失败/错误、4项原设计跳过。相对7S净增21项，旧共同关注1项移入新测试而非丢弃。Corretto1.8.0_492、Maven3.9.9、Boot2.7.18、MyBatis-Plus3.4.3不变。
- 八套显式IT共156项通过，0失败/错误/跳过：关注查询MySQL/Redis9、分类MySQL/Redis8、签到Redis7、评论MySQL82、投递MySQL24、发布MySQL5、编辑MySQL14、Feed Redis7。本机自建MySQL9.6.0/Redis8.6.2及随机localhost端口；默认Surefire不发现IT，这156项独立于566统计。
- 新组件IT使用真实MyBatis SqlSessionTemplate、Redis及旧UserServiceImpl资料适配：DB/Redis分歧不修复、ZSet交集和Long转换/DTO白名单、空/无交集时用户表临时不可用仍成功、缺失用户省略、结果不作顺序保证、坏member/WRONGTYPE/SQL故障不写关系或投影、成员/score/TTL保留、表恢复后新查询可读。仅操纵合成表/键，不宣称跨库一致。

恢复依赖时Central有5个jar读取超时，尚未进入测试；先做有界同制品连通/速度对照，再在临时无凭据settings使用公共镜像、checksum失败拒绝，未改pom或升级版本。5个恢复jar均额外与Central公开SHA-1元数据匹配；这不是完整供应链审计。整合时检查实际Spring Data Redis 2.7.18 API，Tuple无of工厂，夹具使用已有DefaultTuple构造，不改生产Redis协议或放宽业务断言。最终打包已离线重跑通过。

- 最终jar在全新Compose完整54组通过（原52、新2），MySQL8.4.11/Redis6.2.24。新增真实HTTP验证三角色读取、会话actor不可被query替代、匿名/停用/无角色401、正Long参数、数字大ID和UserDTO白名单；原PUT实际建关注后可查，重复PUT仍成功且关系不重复。查询自查不套用写入409。
- 构造DB/Redis投影分歧、缺失/停用展示对象后，验证是否关注仍读DB，共同关注仍读投影，不修复分歧；成员/score/TTL与关系快照保持。坏member、WRONGTYPE与自建表故障仅通用500且状态不被查询改写；关系表临时不可用时已有投影交集仍可取用户资料，finally恢复后正常读取。组件IT/HTTP与源码事实分别限定，不将它们当跨库快照或浏览器大ID无损证明。
- 原分类、签到、评论/回复/审核/审计、动态编辑/撤回、Feed、发布编号、图片、安全、依赖重启、主机jar和prod缺配置拒绝完整回归。脚本清理本次自有资源后，按精确项目标签/镜像名独立读回容器、网络、卷与项目镜像均无残留；14个日志记录私有IT端口无监听。原教学目录HEAD和原未跟踪文件读回未变。
- 30个阶段路径的选定高可信私钥/AWS/GitHub token/个人绝对路径模式无命中，不宣称完整安全审计。8份相关Markdown共332个相对文件链接存在性、脚本AST/机器JSON、git diff --check通过；51条method/path/guard不变，仅两个query归属变化。最终jar内12个变动生产class与编译结果逐字节一致。未读个人.env/凭据、不连共享数据，临时恢复配置与依赖不提交。

以上是选定本机证据，不是生产迁移、容量、HA、浏览器或个人独立掌握认证。

## 部署与回退

原临时开发目录与缓存已不存在；按调试流程先核对原目录和公开远端SHA，再从同一基线创建独立副本，不猜测外部消失原因，原教学目录与未跟踪文件不动。临时无凭据Maven配置、私有依赖目录与测试日志不进仓库。

不新增V015，仍V001–V014，不搬迁/删除用户数据或Redis键。需clean构建完整jar，避免增量输出残留旧Controller方法；满足既有迁移前提时可替换7S，7T回退7S不需数据回退，不表示能回退任意更早版本。验证只用自建服务、随机localhost端口、合成身份与新建Compose项目，不读个人.env/凭据或连接共享数据，不做生产迁移/部署。

选定本机测试不是生产、容量、HA、浏览器或个人能力认证。个人掌握仍需独立确认。按实现→验证→scoped commit→新未参与实现Luna独立静态审查实际SHA→普通push→fetch/readback收口，停在本阶段，最终状态以交付对话为准。
