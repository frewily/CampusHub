# 验证码：缓存生成不等于短信送达

## 这次边界为何这样拆

LoginCodeController只处理原POST /user/code协议、Bean Validation和成功文案；LoginCodeService负责手机号规则、冷却与生成/保存失败的原执行顺序。LoginCodeStore表示冷却占位/保存/释放，LoginCodeGenerator隔离原Hutool实现。原登录服务不再暴露验证码发放，但仍保留通用IService继承及登录事务，不能说整个identity已拆完。

服务复用旧RegexPatterns.PHONE_REGEX纯Java常量，用null或不匹配判断替代RegexUtils的blank+matches；正则本来不能匹配空白，所以行为等价。不trim、不修正旧正则的边界，另用差分测试对照旧工具；未来改手机号范围属于行为变更，不夹在分包中。

## 顺序就是契约的一部分

先SET cooldown key为1，NX加60秒TTL。只有返回Boolean.TRUE才继续；false/null都429。随后原RandomUtil.randomNumbers(6)生成，再SET code key与2分钟TTL。没有真实短信提供商，所以成功文案只沿用兼容响应，不是短信送达证据，也没有强化为新的安全随机方案。

原生成在try外，因此生成异常留下冷却到过期；保存抛RuntimeException时尝试DEL冷却后再抛原异常。若DEL也抛异常，会覆盖保存异常。这些边界本次全部保留，并用故障测试说明；不把它们描述为强一致、必然补偿或生产可靠性保证。

code仍存在但冷却已经不存在时，再次发放会覆盖原code，TTL从新保存开始；不能把两分钟有效期说成两分钟内禁止再次发放。冷却拒绝不刷新旧code/冷却TTL。登录通过原Lua消费同一个key，与本阶段结构迁移分开验证。

## 证据要分层

单测可精确注入生成/保存/清理故障，验证返回Boolean和顺序；自建Redis组件验证key、TTL、手机号隔离和真实存储。全新Compose在独占Redis仅允许冷却key访问，实际拒绝code SET而保留清理DEL，再finally恢复ACL，避免将“SETNX根本没成功”误当保存失败。只操作合成手机号，不调用真实短信或个人账号。

验证前后不记录验证码到公开输出；私有测试目录仅存合成数据诊断，不提交凭据。完整clean构建替换jar，无新DDL/key格式，回退7AA无需数据回退；不承诺移除旧内部方法的二进制兼容。实际结果见[7AB记录](../refactor/50-phase-7ab-login-code-module.md)，不等于生产、短信送达或个人掌握认证。
