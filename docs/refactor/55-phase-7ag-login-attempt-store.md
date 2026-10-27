# Phase 7AG：登录验证码消费与失败计数存储边界

日期：2026-10-27。基线 `e20d8f0d1521e176c4d58c8641fd5fd65ae4fc11`，开始前fetch确认 `0 0` 分歧、工作树干净；继续使用独立副本，不覆盖原教学目录。

## 范围与兼容行为

新增纯Java四方法 `LoginAttemptStore`，由 `RedisLoginAttemptStore` 负责读取原失败计数字符串、执行原验证码消费Lua、执行原计数Lua、删除当前手机号的失败计数。原key、单key脚本、String参数、Long/null返回值、首次600秒有效期和删除false/null忽略规则原样保留；依赖异常不吞、不自动补偿。只移动Redis机制，不移动阈值判断或错误响应。

`UserServiceImpl` 仍在原 `@Transactional login` 中按以下顺序执行：原手机号正则且不trim → 读取/Long解析失败计数 → 已达5次则429 → 缺码/消费非1记失败 → 消费成功后查找/创建账号 → ACTIVE检查 → 签发会话 → 清计数 → 回token。消费null与计数null仍为依赖错误，非1结果与缺码走原失败逻辑；HTTP Bean Validation仍先拒绝缺码请求，不能将服务单测的null分支说成HTTP可达行为。

成功会话签发后清计数异常仍可能留下已签发会话，但HTTP不返回成功token；数据库事务不会回滚已消费验证码、计数或Redis会话。原计数Lua先INCR后EXPIRE，EXPIRE失败不撤销INCR，之后计数大于1不会自动补TTL；本轮明确保留此旧故障边界，不宣称验证码/计数/SQL/会话原子性。也不增加真实短信、自动修复、全量扫描、全端撤销或新限流策略。

生产改动仅原服务与新增端口/适配器；路由仍51映射/19Controllers，DTO、Entity/Mapper、账号仓储、签发器、认证filter、所有Lua、Redis常量、V001–V014与POM不改。登录服务仍依赖Spring事务、旧DTO/Result及校验/阈值常量，不是完整identity模块迁移或纯Java登录用例。

## 验证记录

- 定向30项通过：登录服务19、适配器5、新边界2、既有账号边界3/签发边界1。保留原7项服务场景并将Redis mock换为端口mock，新增顺序、输入、阈值、null/未知返回与异常传播检查；适配器测试检查实际脚本内容、Long返回类型、原始参数与清理结果，不对所有结果默认成功。
- 首次测试编译发现新增测试误用RedisScript包名；依赖API检查同时确认DefaultRedisScript没有getLocation。修正为实际script包，比较classpath脚本内容与Long类型，原断言意图保留；生产代码未因测试兼容修正改变。
- 首次默认clean package共699项，0断言失败、6错误、4设计跳过；6错误来自既有HTTP/健康探针创建本机端口时的 `SocketException: Operation not permitted`。确认环境根因后允许自有临时localhost端口重跑，不修改探针断言或生产配置。最终Java8离线clean package共699项，0失败/错误、4原设计跳过，XML独立汇总一致；比7AF净增19（服务12、适配器5、边界2）。
- 十八套显式IT共219项，0失败/错误/跳过：原十七套214项重跑，加LoginAttemptRedisIT5项。新增真实Redis验证原码只消费一次、错码不续TTL、首次计数600秒而后不刷新、手机号/码/冷却隔离、WRONGTYPE异常及恢复、四线程消费一个成功；不是容量或完整HTTP登录并发证据。自建随机localhost MySQL/Redis；14个日志记录端口另行查询均无监听。
- 逐项替换声明的四次存储调用并去除局部failureKey后，原login及两个私有策略方法的方法体与当前源码在忽略空白时一致；两个旧Lua与基线逐字节一致。此为静态兼容证据，与运行验收分开。
- 全新独占Compose完整84组通过，result.json独立核对组数与MySQL8.4.11/Redis6.2.24。原81组回归保留，新增三组真实HTTP：第5次与已有阈值429且合法code未被消费，后续失败不刷新TTL、HTTP缺码400不记失败、成功只清本手机号计数；WRONGTYPE读计数、拒绝验证码DEL、拒绝INCR均通用500且不丢code/不改账号，权限恢复可登录；首次EXPIRE拒绝留下计数1且TTL=-1，恢复权限后下一次仍TTL=-1，明确手动清理该测试key后再试才重新获得600秒TTL，不冒充自动修复。原SQL注册回滚、会话发布/认证、主机JDK8 smoke和prod缺配置拒绝检查均通过。
- 自有项目 `campushub-phase5-test-9b3d9a5b129c` 的容器、卷、网络、app镜像由脚本清理后独立查询均为0。最终jar的238个应用class/资源与target/classes逐项一致，两个原Lua在包内逐字节一致；HTTP前后有序应用内容SHA-256均为 `afab375a3c3c4462b6746f6bc1f839e5828f8df676ac032a2ac4930509e38873`（按完整条目名排序，逐项摘要输入为UTF-8条目名、NUL、内容）。
- 收尾16个变更路径中生产代码仅原服务与新端口/适配器；diff空白、Python语法、API JSON、711个文档相对文件链接、高置信度敏感模式检查通过。原教学目录HEAD仍为 `e9a365ace19e55ad324f546fb1b022fcd1526f75`，仅保留原未跟踪 `.DS_Store` 与 `docx/`，未覆盖原目录。默认、定向、IT和HTTP数量不相加作为覆盖率。

## 部署与交付边界

无需新schema或Redis格式迁移；完整应用构建需包含新Spring组件，沿用已有登录脚本。仅验证本机独占合成环境，不连接共享/生产服务，不读取个人.env或Maven凭据。测试自有资源清理后按阶段提交、新Luna独立只读审查确定SHA、普通push与fetch/readback结束；最终同步结果在交付对话报告，不继续下一阶段。
