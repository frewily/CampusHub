# 用户资料：会话、数据库与访问策略是三件事

7X只拆用户资料读取，不顺带重写认证。UserProfileController的me返回UserHolder会话中的UserDTO；另外两个GET通过纯Java查询服务和UserProfileReader分别读原UserMapper/UserInfoMapper。资料字段映射移到MyBatis适配器，沿用BeanUtil和ApiModelMapper，不向应用服务暴露持久化实体。

会话里的昵称/图标不必等于数据库最新值。me不额外查询或刷新它们；按ID查询则读取数据库。不能把两种来源混成“统一更正确的资料”而改变原接口。认证过滤器仍会查当前账号状态/角色并可能续期Redis会话，所以“me不调用资料查询”不等于整个请求没有SQL/Redis。

当前三个接口均需有效认证，但没有新增目标归属限制。已认证的三种有效角色仍可按ID读取他人简档；目标停用不额外过滤，详细资料可独立存在而不join用户表。缺失用户/资料成功但不带data，不是404；SQL故障则通用500，不能伪装缺失。这里是保留既有规则，不是认可它们已满足生产隐私需求；调整隐私策略必须另定范围。

简档只有id/nickName/icon，详细资料只有原九字段，ID仍是JSON数字。生日是LocalDate，gender和level仍是原Boolean，不趁拆分改成新枚举/等级模型。密码、手机号、status和时间戳不进入DTO；映射不能修改实体时间戳。

删除旧UserInfo通用服务前查全调用方。IUserService仍被认证及Blog/social使用，本阶段不删除或整体迁移它。实体、Mapper、DTO和共享工具暂留旧包，不能因为出现identity目录就声称完整模块化完成。

验证应区分standalone MockMvc的日期序列化默认值与Boot真实配置，不为修测试改变生产输出。故障验收需要避免同时破坏认证源，否则测到的是过滤器错误而非资料适配器；本轮故障只改资料查询字段并保留认证所需id/status，所有变化均限于可恢复的自建合成环境。

实际本机结果见[阶段记录](../refactor/46-phase-7x-user-profile-module.md)，不是生产或个人掌握认证。个人掌握仍需独立确认。
