# Phase 7AD：登录会话签发端口

开始日期：2026-10-25；2026-10-26额度恢复后继续收口。基线 `6ce37ee57cf14f310590bb60767f0a63a341ce3f`，开始前fetch确认远端一致、工作树干净；恢复时再次核对远端未变，未跨入下一阶段。

## 范围与兼容

只把UserServiceImpl中ACTIVE检查后的UUID/DTO Map/Hash/过期块替换为identity.application.LoginSessionIssuer.issue(LoginAccount)，由@Component RedisLoginSessionIssuer实现。原返回Result(token)、失败计数清理与外层@Transactional保留。移除对应Hutool/DTO/Map导入和教程注释，未重排其他登录逻辑。

原Hutool32位UUID，BeanUtil复制到UserDTO后原CopyOptions的beanToMap、字段编辑器、login:token:{token}、putAll→expire(LOGIN_USER_TTL,MINUTES)顺序不变。只有id/nickName/icon，空串/大ID十进制字符串保留；原ignoreNull选项仍在，但Hutool字段编辑器先将null转空串，因此nullable展示字段不是省略。status不写Hash，不改token格式、TTL策略、UserHolder或SecurityContext。

原expire返回false/null忽略；Hash写异常不继续过期，过期异常仍可留下无TTL Hash、清理失败计数不执行。新账号时DB账号/角色回滚而Hash不回滚，这是旧边界，未修复。不存在新原子协议、事务、DEL补偿或成功后存在性保证。测试清理自有orphan key不是生产补偿。

Controller/51条映射/19个Controller/机器目录不变；验证码、账号仓储、DTO/Mapper/Entity/常量、认证filter/SecurityConfig、Lua、V001–V014和POM不改。不是完整登录分包或生产可靠性提升声明。

## 本轮验证

- 92项定向检查通过，0失败/错误/跳过；恢复后连同3项会话Redis IT重跑95项通过。定向包括签发单测6、签发边界1、账号边界3、账号仓储7、原登录及新增失败顺序7、认证资料HTTP27、API模型32、目录9。原五个登录场景保留，Hash白名单断言迁入签发适配器，补签发异常不清失败计数、清计数失败不撤销已签发结果、当前线程身份不被替换、过期false/null仍成功的原语义。
- 首次Java8编译对照定位到新增测试使用Map.of/Path.of/Files.readString/无参orElseThrow及不存在的template.destroy；仅改成Java8等价写法，连接池/进程仍由原工厂和AfterAll关闭，不升级环境或放宽业务断言。首次定向随后发现“null应省略”的夹具假设不符原Hutool5.7.17：BeanCopier字节码证实先editFieldValue，再ignoreNull检查；原表达式独立对照显示null先转空串。只修预期并加强三字段精确空串检查、同步本阶段文档，生产映射不改；各次失败记录保留。
- 首次Compose在新增过期故障用例得到200而非预期500，未通过本阶段验收。恢复后核对实际Spring Data Redis2.7.18字节码，expire先PEXPIRE、异常回退EXPIRE；只禁用EXPIRE没有注入到真实路径。只改测试同时拒绝/恢复PEXPIRE与EXPIRE，新增私有Redis真实命令拒绝/恢复对照，保留500/无TTL Hash/失败计数/DB回滚精确断言，不改生产代码。首次测试项目已清理，失败日志保留，完整重跑结果另记。
- 恢复后Java8离线clean package共674项，0失败/错误、4项原设计跳过；Corretto1.8.0_492/Maven3.9.9与依赖不变，比7AC净增10（签发6、边界1、原登录补2、账号边界补1）。默认和定向不相加冒充覆盖率，最终应用234个class/资源内容指纹与中断前完全一致。
- 恢复后十五套显式IT共190项，0失败/错误/跳过：新会话Redis3、账号MySQL4、验证码Redis4、登出2、Blog用户4、关注18、资料4、券目录4、分类8、签到7、评论82、投递24、发布5、编辑14、FeedRedis7。自建MySQL9.6.0/Redis8.6.2、合成数据和随机localhost端口，12个记录端口独立确认无监听。会话IT区分客户端包装异常注入与真实Redis ACL双命令拒绝，验证真实Hash/TTL/两会话隔离、无TTL部分写入、权限恢复后的新签发；手工清理仅限记录的测试自有key，不是生产补偿。
- 最终全新Compose完整76组通过（原73、新3），MySQL8.4.11/Redis6.2.24，result.json另行核对组数/版本。新增原UUID格式、三字段字符串Hash/空icon/大ID和分钟TTL、两会话独立及账号行不变；同时拒绝HSET/HMSET时通用500、code已消费、无新Hash且失败计数不清。再同时拒绝PEXPIRE/EXPIRE，对已有账号和注册账号均确认500、写入Hash无TTL、失败计数保留；注册数据库账号/角色回滚而Redis Hash不回滚。finally恢复两命令并只清观察到的自有orphan keys，重新准备合成code后签发/后续认证可用、失败计数清除。全部原场景、主机jar/prod缺配置拒绝保留通过；成功与首次失败两个精确测试项目的容器/网络/卷/应用镜像独立读回均空。
- 最终jar的234个应用class/资源与target/classes逐文件一致，恢复前后及HTTP使用包的有序内容SHA-256均为 `bd139202333967a89c9865f6af9d1b2a7db5f56e73c47a0af158060233da32ce`。签发语句经变量名/注释/空白归一化与原块一致，登录仅将该块替换为端口调用，其余方法体及失败辅助方法未改；静态比较辅助片段边界校正后通过。Controller/机器目录/账号仓储、DTO/utils、安全配置、Lua/迁移/resources及POM无差异。
- 7份相关Markdown的391个相对文件链接、Python AST、机器JSON、git diff --check通过；16个变动路径选定私钥/AWS/GitHub token/个人绝对路径模式无命中，不是完整安全审计。原教学目录HEAD与两个既有未跟踪项读回未变。失败和恢复后各轮日志仅留私有临时目录，不提交实际token/合成凭据；选定本机证据不证明生产。

## 部署与回退

clean构建完整jar后替换；无DDL/key格式变化，回退7AC无需数据回退，不能任意回退更早版本或承诺内部依赖的Java二进制兼容。

只用独立副本、临时无凭据公开Maven配置、自建随机localhost端口组件和全新Compose合成环境，不读个人.env/凭据，不接共享服务/真实短信/生产。ACL故障只对独占Redis，finally恢复，精确清理自有orphan hashes。原教学目录保留；验证、scoped commit、新未参与实现Luna只读审查实际SHA、普通push和fetch/readback后停止，最终状态在交付对话报告。本机证据不是生产、HA、容量或个人掌握认证。
