# 关注查询：区分事实与投影，再拆依赖

## Phase7Y：去掉旧用户服务桥接

以下7T内容保留当时范围。当前共同关注资料由social.infrastructure.MyBatisFollowUserReader(UserMapper)实现：selectBatchIds后沿用BeanUtil映射UserDTO。核对当前MyBatis-Plus 3.4.3字节码，IService.listByIds本来就直接委托该Mapper方法，因此不需要新增SQL或把登录服务搬入social。UserMapper/Entity仍是共享遗留类型，这不是完整模块隔离。

缺失用户自然省略，停用目标仍返回；只保留id/nickName/icon。适配器不重排Mapper结果，但SQL没有ORDER BY，不能承诺输入顺序或固定展示顺序。无交集由原应用服务提前返回，不调用资料SELECT；有交集时SQL异常仍传播为通用500，不静默降级或修复。新增故障验证仅操作独立合成库，保留认证所需id/status/角色表，并在finally恢复phone列。

结构拆分不等于隐私治理；若将来决定过滤停用目标，应另定产品契约，而不是夹带在适配器替换中。7Y无数据迁移，回退7X用完整clean构建包即可；不承诺删除的内部Java类型二进制兼容。具体本轮证据见[7Y记录](../refactor/47-phase-7y-follow-user-reader.md)。

实际开发照着走：先对照三个关注接口的完整调用链→单独提取两个GET→把SQL、Redis和用户映射放进读取适配器→保留写入与发布链→用分歧数据和故障测试验证读语义未变→最终jar验收。继续条件是旧契约有证据且没有顺手改变一致性规则，不是“多了social目录”。

可复制Prompt：只把isFollow/common从旧Follow服务提取为social查询用例，URL、认证、正Long校验和响应不变。是否关注读MySQL COUNT，共同关注读follows:{id} ZSet交集，经用户服务映射UserDTO；不分页、不回填、不改写关注或Feed链。保留已有回归断言并测试DB/Redis分歧、无交集、坏member、WRONGTYPE和SQL异常，明确只读边界。

## 三个端口对应三种职责

FollowLookupRepository：关系事实的COUNT。FollowProjectionReader：原ZSet成员读取，不推断事实、不修复。FollowUserReader：按交集ID取资料并映射旧UserDTO。应用编排不依赖Spring、MyBatis、Hutool、ThreadLocal或通用IService，但仍复用旧UserDTO；用户端口的Legacy桥接依赖旧IUserService，不伪装identity模块已完成。

用户ID只在Controller从认证会话取得；外部query userId不代替当前用户。自查允许，不应把写入“不能关注自己”的409搬到读取。写入角色及字节级实现不变，Blog发布仍用旧服务查询关注者。

## 事实与投影不能混说

MySQL有关系而Redis漏成员时，isFollow可为true，common可能为空；Redis包含旧成员但MySQL没有对应关系时，common仍按投影取资料。目标用户行缺失会自然省略；展示对象停用不会自动增加新过滤。这里保持旧语义，并非宣称这种投影已经强一致或永不丢失。

common原样读取两个ZSet全量范围并用HashSet求交集，再将字符串转Long，结果不承诺顺序或分页快照。坏交集member、WRONGTYPE和SQL错误不吞掉、不伪装空成功。查询不改关系/投影、分数或TTL；认证会话续期属于既有过滤链，不能把整个HTTP请求说成“绝无Redis写”。

## 验证与回退

迁移原ZSet回归用例而非删除断言；旧写入用例留在原测试。组件IT用SqlSessionTemplate和自建MySQL/Redis，避免测试一级缓存掩盖真实SQL。代码版本决定依赖API，应检查实际jar而非凭记忆使用静态工厂。HTTP和组件证据分开，源码/Bean发现又是另一层。

全新独立副本执行clean package，完整jar替换；移除旧方法意味着不承诺外部Java调用者二进制兼容。无新schema或Redis格式，7T回退7S无需数据回退，但原有更早版本限制仍适用。个人掌握仍需独立确认。
