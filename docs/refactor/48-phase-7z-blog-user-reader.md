# Phase 7Z：动态作者与点赞人资料读取端口

日期：2026-10-23。基线 `67dcc44f982789efdf49e1878adcd47464628198`；开始前fetch确认与远端main一致、工作树干净。

## 范围与兼容

BlogServiceImpl原IUserService依赖替换为content.application.BlogUserReader，由@Component MyBatisBlogUserReader(UserMapper)实现。作者用原selectById后映射UserDTO；原caller仍填name/icon，缺失null依旧导致异常，不新增占位或404策略。热门、详情与Feed继续共用作者展示；原只查当前用户动态/指定用户动态的Controller列表不增加资料填充。

点赞列表仍先确认动态可见，再ZRANGE blog:liked:{id} 0..4，无/null成员提前空列表；按原成员顺序Long解析后，适配器将原IN、ORDER BY FIELD和BeanUtil DTO映射搬入基础设施。缺失用户省略不从第六名补位，停用目标/作者不新增过滤，数字ID、DTO三字段和null省略不变；没有缓存、join、新排序、额外状态查询、事务或错误降级。FIELD只拼已解析Long，原始字符串不直接进入SQL。

Controller、SecurityConfig、登录/验证码/登出、用户Entity/Mapper/DTO、资源迁移V001–V014不改；仍51条映射/17个Controller，机器路由清单不变。Blog编辑/撤回/点赞/发布/Feed其他方法体保持原规则，投递端口和事务不变；仅替换资料调用与删除对应教程注释。IUserService在生产只剩认证Controller/实现使用，但仍保留IService继承，不代表整个identity/content已迁移。

## 本轮验证

- 最终Java8离线clean package共632项，0失败/错误、4项原设计跳过，比7Y净增13；Surefire XML按默认/显式IT分组读回一致。新增适配器5、模块边界2、原Blog编排6项，验证字段映射/时间戳保留、SQL参数与FIELD顺序、异常传播、缺失作者原异常、空/null点赞跳过资料、非法成员先失败和前五不补位；新增13项另行定向通过。原7份Blog测试夹具由IUserService改为窄端口，保留原断言，发布/编辑/撤回/Feed场景不删。
- 首次默认632项中1项新增边界断言失败：误将Java接口getSuperclass()期望为Object；系统化调试核对失败与接口反射语义后改为isInterface/无父类/无父接口，并加强源文件不依赖框架/Entity的检查；只修测试假设，不改生产类型或删边界检查。原失败记录保留，13项定向和完整clean package重跑通过。
- 十一套显式IT共177项，0失败/错误/跳过：新Blog用户读取MySQL4、关注读写18、用户资料4、券目录4、分类8、签到7、评论82、Feed投递24、发布5、编辑14、FeedRedis7。真实MyBatis/SqlSessionTemplate、自建MySQL9.6.0/Redis8.6.2、随机localhost端口与合成数据；新IT验证停用大ID映射、逆数值顺序FIELD与缺失省略、当前数据库非缓存读取、真实SQL故障/finally恢复和完整行不变。日志记录的8个私有端口独立确认无监听；默认与IT不相加冒充覆盖率。
- 首次Compose前置旧业务场景通过后，在新增账号准备阶段被手机号唯一键拒绝，未进入新增HTTP场景及后续部署收尾。私有MySQL日志给出重复合成手机号，脚本对照确认新7200/7300范围与既有关注/发布夹具重叠；只换成专属992701–992703/992801–992805的11位合成手机号，并增加插入前全部不存在的断言。没有删约束、忽略重复或改登录规则；首次项目已清理，保留失败日志，最终另建全新项目重跑通过。
- 全新Compose完整65组通过（原63、新2），MySQL8.4.11/Redis6.2.24，result.json另行核对组数/版本。新增三角色的跨用户详情/点赞人列表、匿名热门、实际Feed作者展示；数据库作者名与会话名不同，作者/点赞人停用仍保留，三字段白名单与数字大ID精确不变。真实ZSet六成员的前五包含缺失用户，返回四行且不补第六名，FIELD顺序不是数值排序。仅临时隐藏phone列，保留认证id/status/角色读取，详情/点赞人/Feed资料查询通用500，无点赞仍空列表；finally恢复列后可读。新增坏member先失败、孤立作者仍500（测试独占孤立动态在finally精确删除，避免污染后续热门）。两用户集/动态行完整事实和ZSet成员/分数快照不变，全部旧场景及主机jar/prod缺配置拒绝仍通过。成功与失败两个精确项目的容器/网络/卷/应用镜像均独立读回为空。
- 最终jar的217个应用class/资源与target/classes逐文件一致，HTTP前后有序内容指纹均为SHA-256 `08fc5d105fcdad17b3188e1c5e34e43a9da253d2e265cdcb0d5ab0b7856c0fee`。旧Blog的withdraw/edit/hot/detail/isLiked/like/save/feed共8个方法体与基线逐字节一致；只改资料helper/点赞人读取部分。生产Controller、认证/安全配置、IUserService/Impl、resources/迁移与机器路由清单无差异；原教学目录HEAD及两个未跟踪条目未改。
- 6份相关Markdown的320个相对文件链接、Python AST、机器JSON、diff --check通过；21个变动路径选定私钥/AWS/GitHub token/个人绝对路径模式无命中，不是完整安全审计。构建、定向、IT和两次Compose私有日志保留，不随代码提交。

## 部署与回退

完整clean构建后替换jar；无DDL/Redis格式变化，回退7Y不需要数据回退，更早阶段限制仍适用。内部依赖类型改变，不承诺旧Java调用者二进制兼容。

只使用独立开发副本、临时无凭据Maven配置/缓存、自建localhost随机端口MySQL/Redis和全新Compose合成数据；不读个人.env/凭据、不连接共享服务、不部署生产。原教学目录与无关文件保留。验证、scoped commit、新未参与实现Luna独立只读审查实际SHA、普通push与fetch/readback后停止；最终状态在交付对话报告。不是生产、HA、容量、浏览器或个人掌握认证。
