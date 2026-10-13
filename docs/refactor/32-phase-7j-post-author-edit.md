# Phase 7J：动态作者编辑

日期：2026-10-13。文档基线 `46714adbb60327163533e58d08378047049295f8`。本阶段为现有校园动态增加作者本人编辑入口；教学仓库和旧临时目录保持只读。

本文记录已实现的接口与数据边界；选定本轮验证、文档收尾和隔离资源清理完成。实际提交后由未参与实现的 Luna 独立静态审查，再普通推送并读回远端；最终 SHA、审查和推送状态在交付对话报告，不在此提前宣称。

## 契约

新增 `PUT /blog/{id}`，处理器为 `editBlog`。请求 Path `id` 绑定正 Java `Long`。仅 USER、MERCHANT、ADMIN 可调用；三种角色都只能编辑存量动态的作者本人，ADMIN 没有越权覆盖。匿名、账号停用、身份未知或没有有效角色时为 401；编辑他人动态为 403；目标不存在为 404。

JSON body 是完整替换请求，不是局部 PATCH：

| 字段 | 规则 |
|---|---|
| `title` | 非空白，最多 255 |
| `images` | 可为 null；原始值最多 2048；按存量动态的门店关联规则校验 |
| `content` | 非空白，最多 2048 |

未知 JSON 字段忽略。客户端提供的 `id`、`userId`、`shopId`、liked/comments 计数和时间字段均不参与更新。完整替换只覆盖上述内容字段；作者和门店关联从存量行读取并保持不变。

`shop_id` 非 NULL 的门店关联动态仍要求 `images` 非空白，即使请求中的 null/空白不能清空其图片。无门店关联动态允许 null、空串或 `String.trim()` 后为空白，并归一化为非 null 空串 `""`；合法非空原串不 trim、不改写。该规则与纯文字发布时的无图存储语义一致。

成功返回 `Result.ok()`，无 `data`。参数或关联动态无图为 400；更新影响行数不是 1 为 422；数据访问异常映射为不带 cause 的通用 500。

## 写入与并发边界

读写位于事务中。先用 `SELECT ... FOR UPDATE` 对目标动态做当前读并持有行锁，再依据读到的 `user_id` 和 `shop_id` 判断作者与图片约束。更新 SQL 只绑定 `title`、`images`、`content`，不能用全 Entity 更新覆盖并发变化的 liked/comments 等字段。单条语句 timeout 为 5 秒；这不是接口 SLA 或性能承诺。

当请求的三个内容字段与锁定行完全相同时，不发 UPDATE，也不刷新 `updateTime`。两个编辑事务按行锁串行；后获得锁的合法写入可以覆盖先前已提交的内容。当前没有版本号、If-Match 冲突检测、历史版本或编辑审计，因此不能声称系统能检测或避免后写覆盖。

本阶段不改变 `createTime`、作者、门店关联、点赞/评论计数、评论、审核审计或 Redis 数据；不删除图片文件，不重投或重排 Feed。既有 Feed 消费路径在读取动态 ID 后会查询当前已提交的数据库内容，但这不构成可靠投递，也不保证跨请求冻结的内容快照。

## 结构、兼容与范围

不增加表、列、索引或迁移，V001–V009 保持不变。应用 API 映射为 47 条、仍来自 13 个 Controller；BlogController 为 9 条映射。评论 Controller 的 6 个方法和 ADMIN 评论 Controller 的 3 个方法不变。旧 API 目录条目保留，仅新增本条 PUT 契约。

本阶段不增加动态删除、审核、前端、图片来源、XSS 处理或新的可靠 Feed 机制；也不顺带修复点赞/Feed 的既有问题。应用回退不需要反向 DDL，不撤销已提交的内容；旧应用缺少编辑路由，不保证继续接受新 PUT 请求。不删除动态或旧图片文件来回退业务内容。

## 本轮验证

- **83 项定向检查**通过，0 失败/错误/跳过：新编辑服务 6 项、HTTP 5 项、目录 5 项，旧动态服务 5 项、API 模型 HTTP 31 项、安全 HTTP 19 项、纯文字请求 8 项/HTTP 4 项。覆盖正数/溢出 ID、完整 body/原始长度、身份、三角色方法授权、本人/他人、存量关联图片规则、未知字段白名单、exact no-op、零行守卫与无 cause 500、无其他资源调用、成功无 data、请求后身份清理。HTTP 服务/账号/Redis为 mock，不用其证明真实数据库授权；本人校验在服务及实际验收另测。
- **Java 8 clean package 398 项默认统计**通过，0 失败/错误、4 项原有教学实验设计跳过；Corretto 1.8.0_492、Maven 3.9.9，Boot 2.7.18 / MyBatis-Plus 3.4.3 不变，专用离线缓存与必要本机监听权限。默认 Surefire 不发现 *IT，不能代替实际数据库验收。
- **60 项显式 MySQL IT**通过：新增 BlogEditMySqlIT 7 项、原 BlogPublicationMySqlIT 4 项和 BlogCommentsMySqlIT 49 项。本机独立临时 MySQL 9.6.0/随机 localhost 端口，真实 MyBatis XML 与 Spring 读写事务代理，拦截器断言编辑锁读/更新实际处于可写事务。测试自建行的作者/关联/liked/comments/create_time 与评论保留；纯文字/门店规则、非法请求/身份、403/404、重复不调用 UPDATE且不变 update_time。数据库触发器失败为通用 500 并保留原行；真实 UPDATE 成功后测试专用返回 0，触发生产 422 守卫且实际修改整体回滚，不是假 SQL 失败。独立连接在真实 UPDATE 后而提交前读到旧标题，提交后的新读取看到新标题；工作线程身份、latch、线程池、context与mysqld按 finally/生命周期清理。资料/关注/Redis为 mock，不冒充真实 Redis。
- **28 组目标 Compose 验收**通过（原 26 + 新 2），使用本轮 Java 8 jar、MySQL 8.4.11 / Redis 6.2.24 与合成身份。三角色实际创建本人动态后编辑成功；USER/MERCHANT/ADMIN 对他人动态均 403，匿名/停用/无角色/未知角色 401。伪造作者/关联/主键/计数/时间字段不参与更新，存量门店无图即使请求 shopId=null 仍拒绝；标题/正文/图片边界、缺失/非法 path、non-null 空图与合法非空原串已验。实际评论/回复/管理员隐藏审计和点赞先建立，再编辑，作者/关联/create_time/计数、根/回复/审计和 Redis点赞/专属关注者 Feed 成员及分数保留；选定 Feed 首屏读到编辑后内容，没有重投或重排。不宣称全 Feed 分页/并发或可靠投递。只对专属动态生效的 UPDATE-rejecting 触发器下，相同内容仍 200，改内容为通用 500，DB状态/计数/update_time回滚且 Feed不变。原内容/审核/审计、部署、图片、重启、依赖故障、主机 jar/prod 配置实际回归；私有 result.json 独立读回为28组及上述版本。
- 自建 Compose 项目 finally 清理；独立只读确认本轮容器、网络、合成卷、项目应用镜像均无残留，测试进程退出。仅移除可重新生成的本次测试资源，保留基础镜像/其他项目、共享服务及原教学目录预存未跟踪文件；日志与私有结果不提交。
- 收尾针对 19 个阶段文件：私人路径/密钥模式无匹配，7 份 Markdown 围栏/相对文件链接存在性、47 行应用 API 表列宽/机器目录（13 Controllers、Blog 9、comment 6/admin 3）、两个独立 Actuator GET、Python AST、bootstrap shell 语法、git diff --check通过；文档收尾后重跑专项。不是全历史密钥审计、链接行锚点语义验证或全部47端点完整端到端验收。

静态汇合核对发现文档误写 BlogController 为10，按系统化调试对照源码/机器目录校为9；未为此改业务。Compose 复合触发器在运行前补齐客户端 DELIMITER 分隔符，不虚构为一次运行失败。调试辅助文件/关联技能未提供，按已读取主流程完成定位与验证。旧阶段记录不反向改写，本轮未重跑其他领域 IT、性能原始实验或生产负载，无吞吐/延迟/容量/SLA 数字。

## 复现与出口

```bash
./mvnw clean package
./mvnw -Dtest=BlogEditMySqlIT,BlogPublicationMySqlIT,BlogCommentsMySqlIT test
python3 scripts/verify-compose.py
```

要求 Java 8、mysqld、本地 Docker和必要本机监听权限，只使用自建隔离资源/合成身份，不读取个人 .env/凭据或连接共享 DB/Redis。

实际提交后新 Luna 独立只读审查，通过后普通推送及远端 readback，最终 SHA/审查/同步状态在交付对话报告。完成本阶段即停，不自动增加动态删除、乐观版本、编辑历史、可靠 Feed 或 UI。
