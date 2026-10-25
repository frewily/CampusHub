# 登录需要账号存取，不需要通用用户CRUD

## 本次真实边界

IUserService原只剩login，但仍继承整套IService；UserServiceImpl为两个账号动作继承ServiceImpl。7AC让它只依赖LoginAccountRepository的findByPhone/createWithPhone，返回不可变LoginAccount(id/nickName/icon/status)。模型和端口是纯Java，不向应用暴露User Entity、MyBatis或通用CRUD；DTO/Mapper/Entity仍在旧共享包，登录编排也仍在旧服务，不能说整个identity已迁移。

原QueryChainWrapper.eq(phone).one落到UserMapper.selectOne；新适配器使用相同QueryWrapper。创建仍原user_前缀加10位RandomUtil昵称、ACTIVE、UserMapper.insert后insertDefaultUserRole。两次插入的返回值原来就不检查，本次不夹带零行失败判断或唯一键重试。已有账号不关联角色查询、不新增状态过滤；状态检查仍在验证码消费后的登录服务。

## 数据边界也有细节

内部四字段不是新的HTTP响应。登录在原位置检查ACTIVE，再BeanUtil复制到旧UserDTO、写原字符串Hash和TTL，不把手机号、密码或status写入会话。旧User实体icon有空串默认值；MyBatis默认不对SQL NULL调用setter时，读取结果可能保留空串，新仓储必须与旧Mapper实际映射一致，不能凭SQL NULL推断成响应null。

## 谁拥有事务

原login上的@Transactional仍是边界，仓储不新开事务，也不自行提交。账号INSERT与默认角色INSERT处于同一外层MySQL事务，角色插入失败可回滚账号；自增ID可能留下间隔，不承诺序号连续。

Redis验证码已在账号SQL前被原Lua消费，SQL回滚不会恢复它；返回失败后要重新获得有效验证码。会话也不成为跨MySQL/Redis事务，不外推为所有故障原子性。本阶段不重写已有失败计数、验证码消费、登录/登出协议或并发注册冲突策略。

## 如何核实

单测检查绑定phone、四字段、生成ID传递、默认角色SQL、零返回值与异常顺序；组件用真实MyBatis和外层TransactionTemplate验证数据库事务，不冒充完整生产login代理。全新Compose另用实际应用login代理及角色触发器验证真实HTTP回滚与Redis码已消费，再恢复触发器重试；其他HTTP场景验证已有三角色、停用账号、无角色账号及会话白名单。

完整clean构建替换，无DDL/key格式变化，回退7AB无需数据回退；移除内部CRUD继承不承诺Java二进制兼容。具体结果见[7AC记录](../refactor/51-phase-7ac-login-account-repository.md)，不等于生产、容量或个人掌握认证。
