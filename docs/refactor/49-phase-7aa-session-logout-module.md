# Phase 7AA：会话登出模块

日期：2026-10-25。基线 `c251846c44065d130893445f05d27e951ad75352`。

## 恢复与范围

原临时副本的.git缺少HEAD等必要文件，不能作为有效仓库，且构建缓存部分POM元数据缺失。保留旧目录，从CampusHub远端main另建独立副本，核对实际远端/本地均为上述已交付SHA、干净工作树后开始。本轮通过临时无凭据settings恢复公开依赖元数据，使用原Java8/Maven版本，不改个人配置或全局软件；旧阶段结果不是本轮验证证据。不推测缺文件的来源，原教学目录保留。

只把POST /user/logout迁为identity.api.LogoutController→SessionLogoutService→SessionTerminator→RedisSessionTerminator，配置类显式装配纯Java服务。旧UserController/IUserService/Impl删除logout，其余验证码/登录与认证过滤器不动；当前51条应用映射/18个Controller，机器清单只改登出Controller归属，V001–V014不变。

## 保留的语义

- authorization原token、首尾trim，无Bearer解析；应用内部null/trim空仍AUTHENTICATION_FAILED/“认证信息缺失”，HTTP受认证filter保护。有效USER/MERCHANT/ADMIN均可登出，匿名/空白/过期/停用/无有效角色仍401；禁用/无角色会话的清理由原filter承担，不新增登出权限。
- 只DEL login:token:{当前token}，随后清理当前UserHolder；DEL返回false/null同样清理并成功，不检查删除条数。Redis抛错原样传播为HTTP通用500，适配器不提前清UserHolder；原filter finally仍清线程身份/SecurityContext。不新增事务、TTL策略、全账号扫描/删除、会话代际或并发请求终止协议。
- 成功Result.ok无data；已撤销token再次HTTP调用401，不把内部重复删除成功说成HTTP重复200。外部query userId/token不替代请求头，其他token保留。验证码/登录与schema/Redis格式、用户资料读取不改。

## 本轮验证

- 90项定向检查通过，0失败/错误/跳过：新服务3、适配器4、实际安全过滤链MockMvc5、登出模块边界1，原登录/验证码7、资料边界2、认证资料HTTP27、API模型32、路由目录9。原UserServiceImplTest登出用例迁入新适配器测试，保留trim/身份清理与成功响应断言，原登录用例和清理不删。
- 本轮最终Java8 clean package共644项，0失败/错误、4项原设计跳过；Corretto1.8.0_492、Maven3.9.9及依赖版本不变。比7Z净增12（新13项、原登出1项迁出），定向已包含在默认统计内，不叠加冒充去重覆盖数量。
- 首次定向编译发现迁移夹具误删仍被登录断言引用的LOGIN_USER_KEY导入，只恢复导入不删原断言。随后测试插件公开POM下载遇到一次TLS连接中断，未关闭证书检查、未读取个人配置，重试恢复。运行时发现新HTTP成功夹具包含制表符，被原StrictHttpFirewall先拒绝；只改HTTP成功夹具为合法首尾空格，保留应用层制表符trim测试，并新增控制字符头必须被防火墙拒绝的独立回归。90项重跑通过；各次失败日志保留，生产安全配置不改。
- 全新Compose完整67组通过（原65、新2），MySQL8.4.11/Redis6.2.24，result.json另行读回组数与版本一致。新建5个专属合成账号、每个两会话，检查三角色合法空格trim、精确success无data、外部query不能换token、只删当前会话、旧token重放/读取401、同账号另一会话内容和未请求前TTL不变；匿名/空白/过期/停用/无角色401及原filter只清当前无效会话语义保留。独占Redis临时禁止DEL而保留认证HGETALL/EXPIRE，确认认证后登出通用500、会话仍在、后续匿名请求不获得身份；finally恢复ACL后原会话可读、重试撤销成功。账号/角色事实行不变，全部旧场景/主机jar/prod缺配置拒绝通过。精确Compose项目容器/网络/卷/应用镜像独立读回均空。
- 十二套显式IT共179项，0失败/错误/跳过：新登出Redis2、Blog用户MySQL4、关注18、资料4、券目录4、分类8、签到7、评论82、投递24、发布5、编辑14、FeedRedis7。使用自建MySQL9.6.0/Redis8.6.2和合成数据；新IT验证实际当前key删除、重复内部删除成功、相同账号另一会话及其TTL/无关键保留、trim/身份清理。9个记录私有端口独立确认无监听，默认/定向/IT统计不相加冒充覆盖率。
- 本轮最终jar共222个应用class/资源条目，与target/classes逐文件一致，最终clean package和HTTP使用包的有序内容SHA-256均为 `efa24bc94abda092fb72c296ea9cd9c2b43cc77910246219b3c01aae81f72d0e`；Surefire默认XML独立统计644/0失败/0错误/4跳过。旧用户服务5个登录/验证码辅助方法和Controller两个方法体均同基线逐字节一致；安全filter/config、resources/迁移和依赖声明无差异，51映射只改logout的controller归属。
- 7份相关Markdown的362个相对文件链接、Python AST、机器JSON、git diff --check通过；27个变动路径选定私钥/AWS/GitHub token/个人绝对路径模式无命中，不是完整安全审计。原教学目录HEAD和两个既有未跟踪条目读回未变；各次构建/失败/回归日志只留私有临时目录，不提交到仓库。

## 部署与回退

完整clean构建替换jar，避免原Controller方法残留导致重复路由；无DDL或会话格式迁移，回退7Z无需数据回退，不支持任意更早版本或旧内部Java方法二进制兼容。

仅使用独立开发副本、临时公开无凭据Maven配置与缓存、自建localhost随机端口Redis/MySQL、全新Compose合成数据；不读个人.env/凭据，不连接共享服务或部署生产。HTTP故障仅在独占Redis临时禁止DEL，在finally恢复权限；不是生产ACL变更。验证、scoped commit、新未参与实现Luna独立只读审查实际SHA、普通push、fetch/readback后停止，最终状态在交付对话报告。本机证据不是生产、容量、HA或个人掌握认证。
