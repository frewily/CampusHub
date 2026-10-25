# Phase 7AB：验证码缓存发放模块

日期：2026-10-25。基线 `ba3c93595c9a0935eb709245e3d8ab84bcfbef0e`。开始前一次Git TLS连接中断，保持证书校验重试成功；fetch确认本地/远端main一致且工作树干净，未修改网络或个人配置。

## 范围与兼容

POST /user/code迁到identity.api.LoginCodeController，handler sendCode、原NotBlank/Pattern/RequestParam消息、公开权限和Result.ok“发送验证码成功”不变。应用LoginCodeService依赖LoginCodeStore/LoginCodeGenerator，适配器使用原Redis/Hutool。旧UserController/IUserService/Impl移除sendCode，只保留登录；当前51条应用映射/19个Controller，机器清单仅改验证码Controller归属。

保留原手机号正则常量、不trim，null/不匹配仍VALIDATION_FAILED“手机号格式错误”。RegexUtils的blank+matches与直接null+matches等价，边界不修正；应用不依赖该Hutool工具，但暂复用RegexPatterns纯常量，非完整shared迁移。

冷却key/1值/NX/60秒、只有Boolean.TRUE继续（false/null都429）、原RandomUtil六位数字、code key/2分钟TTL不变。生成仍在try外，生成抛错不清冷却；save RuntimeException时尝试DEL冷却再抛，DEL失败仍覆盖原错误。不新增事务、重试、Lua冷却协议、并发代际、TTL策略、日志验证码、SMS或账号写入。只发放不写DB，不代表短信送达。

登录方法、其事务/验证码Lua消费/失败计数/用户角色创建、登出与认证过滤器、安全配置、DTO/RegexPatterns/RedisConstants和V001–V014均不变。不承诺修复所有并发故障或原手机号正则策略。

## 本轮验证

- 88项定向检查通过，0失败/错误/跳过：新服务5、Redis存储4、生成器1、真实Spring校验/安全链MockMvc2、模块边界1，原登录5、资料边界2、认证资料HTTP27、API模型32、目录9。保留旧两项验证码断言的冷却拒绝/不存新码、TTL/单位、六位格式与成功文案/不回码，迁到适配器/应用/HTTP测试；原登录五项不删。新服务验证false/null、原正则逗号字符类边界、输入不trim、占位/生成/保存/清理异常顺序和异常覆盖；边界明确允许共享纯Java RegexPatterns常量。
- 最终Java8离线clean package共655项，0失败/错误、4项原设计跳过；Surefire XML独立统计一致，比7AA净增11（新增13、原验证码2项迁出）。Corretto1.8.0_492/Maven3.9.9及依赖不变；定向已在默认中，不相加冒充覆盖。初次定向编译定位到迁移夹具漏保留原登录断言仍使用的Mockito never导入，只恢复导入，原断言/失败记录保留；随后88项重跑和完整clean package通过，未为测试改生产行为。
- 十三套显式IT共183项，0失败/错误/跳过：新验证码Redis4、登出2、Blog用户4、关注18、资料4、券目录4、分类8、签到7、评论82、投递24、发布5、编辑14、FeedRedis7。自建MySQL9.6.0/Redis8.6.2、随机localhost端口与合成数据，10个记录端口独立确认无监听。新IT验证存储/TTL、手机号隔离、删除本测试冷却key后覆盖旧code；主动缩短已有夹具TTL后毫秒级比较，防止错误刷新被秒级取整掩盖。IT保存失败用Java包装注入异常并检查真实Redis删除，不伪称真实Redis写故障；实际写故障另由Compose验证。
- 全新Compose完整70组通过（原67、新3），MySQL8.4.11/Redis6.2.24，result.json独立读回组数/版本。专属合成手机号预检无账号/key占用，匿名和无效token均可调用公开发放接口；精确成功文案无验证码、六位数字、60秒/120秒key与TTL、重复429内容/TTL不刷新、手机号隔离、缺失/空白/格式错误400及发放不写DB均验证。独占Redis ACL仅允许冷却key，确认SET NX后code SET被真实拒绝、冷却DEL成功、通用500且无code写入；finally恢复ACL，重试可用，其他手机号内容不变。最后用原登录链消费实际生成的合成验证码、创建USER/session，并以原登出撤销，全部旧场景和主机jar/prod缺配置拒绝通过。精确项目容器/网络/卷/应用镜像独立读回均空。
- 最终jar的229个应用class/资源与target/classes逐文件一致，HTTP前后内容指纹均为SHA-256 `d8322dfffe436621257745bf1ed0d6f85dec543e85b92d4d56bbcc167599882a`。原登录服务4个方法与Controller登录方法体同基线逐字节一致；认证/登出、DTO/utils、resources/迁移和POM无差异，机器清单仅验证码controller归属变化。原教学目录HEAD及两个未跟踪条目读回未变。
- 7份相关Markdown的374个相对文件链接、Python AST、机器JSON、diff --check通过；30个变动路径选定私钥/AWS/GitHub token/个人绝对路径模式无命中，不是完整安全审计。所有运行日志/合成凭据只留私有临时目录，不提交。

## 部署与回退

clean构建完整jar后替换，防止旧Controller残留；无DDL/key格式变化，回退7AA无需数据回退，不支持任意更早版本或移除内部方法的二进制兼容。

仅在独立副本、临时公开无凭据Maven配置、自建localhost随机端口进程和全新Compose合成环境验证，不读个人.env/凭据、不接共享服务、不发真实短信或部署生产。ACL故障只针对独占测试Redis，finally恢复。原教学目录保留。按验证、scoped commit、新未参与实现Luna只读审查实际SHA、普通推送和fetch/readback后停止；最终状态在交付对话报告。本机证据不是生产、HA、容量、短信送达或个人掌握认证。
