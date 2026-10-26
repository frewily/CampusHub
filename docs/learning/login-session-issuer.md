# 会话签发：返回token不等于跨存储原子性

## 这次拆出什么

LoginSessionIssuer只接受内部LoginAccount，返回token字符串；RedisLoginSessionIssuer保留原Hutool UUID、UserDTO映射、字符串Map、Hash写入和过期设置。原登录服务仍负责手机号校验、码消费、账号/状态检查，签发成功后才清理失败计数、返回Result。端口不依赖Redis/Spring/Entity；适配器仍使用旧DTO和常量，不把这一步称为完整identity迁移。

会话只写id/nickName/icon，保留空字符串，大ID转十进制字符串；status、phone、password不写入Hash。原Hutool配置虽设置ignoreNull，但字段编辑器先把null转空串，因此nullable展示字段实际仍以空串进入Hash，不应误说成省略。这里没有JWT或Bearer协议迁移。签发不主动保存UserHolder或SecurityContext，原请求过滤器仍负责当前请求身份生命周期。

## 保留的时间与错误规则

LOGIN_USER_TTL仍按分钟传给Redis，不能把常量值改当秒。先putAll，再expire；expire返回false/null原来就忽略，本阶段不新增判错。putAll抛错不继续expire；expire抛错不撤销Hash，异常向上传播，失败计数不会继续清理。

因此过期设置失败可能留下无TTL Hash。已有账号的Hash可能继续存在；新账号数据库事务会回滚，但Redis中的Hash不跟随回滚。未返回给调用方的token也不能据此当作已经成功登录；测试为了观测旧边界会读取独占Redis中的自有新key，随后精确删除，这只是测试清理，不是生产补偿。

如果后续要修复这条边界，应单独选择原子Hash+TTL、失败补偿与并发撤销的协议，写清发布兼容及故障要求，而不是在分包时默默改变规则。

## 如何验证

单测精确核对映射/顺序、TTL单位、false/null和错误传播；独立Redis组件核对真实Hash、TTL、两会话隔离。当前Spring Data Redis的expire方法先用PEXPIRE，抛错时回退EXPIRE，所以实际过期故障须同时拒绝两命令，不能只看Java方法名；组件和全新Compose均对此真实注入。SET EX形式的合成验证码/计数准备仍可用，从而确保故障发生在原签发阶段。ACL均finally恢复，孤立Hash只按本测试观察到的确切key清理。

完整clean构建替换，无新DDL/key格式，回退7AC无需数据回退；不承诺内部依赖变更的Java二进制兼容。实际证据见[7AD记录](../refactor/52-phase-7ad-login-session-issuer.md)，不等于生产、容量或个人掌握认证。
