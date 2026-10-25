# Phase 7AC：登录账号存取边界

日期：2026-10-25。基线 `747d4208c4529438a75010a33ab8e5e80b15f146`。开始前fetch确认与远端main无分歧、工作树干净。

## 范围与兼容

UserServiceImpl登录只把原query().eq(phone).one和createUserWithPhone替换为identity.application.LoginAccountRepository的findByPhone/createWithPhone，内部LoginAccount四字段id/nickName/icon/status；IUserService/Impl去掉IService/ServiceImpl继承。新MyBatisLoginAccountRepository(UserMapper)使用原selectOne/QueryWrapper.eq(phone)，缺失null、不join角色或新筛选status；创建仍原User、user_加RandomUtil10字符、ACTIVE、先insert再insertDefaultUserRole，忽略原来就不检查的返回值。Mapper/Entity/DTO暂留旧包。

login上@Transactional、手机号校验、失败计数、验证码Lua消费与状态判断顺序、UUID token、BeanUtil/CopyOptions会话三字段/字符串/TTL/失败计数清理保留。状态检查仍在消费后，无角色ACTIVE账号登录可先成功而后续filter拒绝。仓储不新增事务、缓存、重试、唯一冲突恢复或insert行数检查；外层DB事务仍覆盖账号和角色，Redis副作用不加入DB回滚。

Controller/路由/认证filter/SecurityConfig、验证码/登出、DTO/Entity/Mapper/常量、Lua和V001–V014/POM不变。51条应用映射/19个Controller和机器API清单不变，不引入新产品能力或完整identity迁移声明。只移除旧创建helper及对应持久化依赖，登录其他逻辑不重排。

## 本轮验证

- 82项定向通过，0失败/错误/跳过：新仓储单测7、边界2，原登录5、认证资料HTTP27、API模型32、目录9。保留五个原登录场景，原默认角色SQL断言迁入仓储单测；覆盖phone绑定/四字段/大ID/disabled/null状态、昵称前缀和10字符、生成ID传给默认角色、原零行结果忽略和异常次序。内部模型/端口纯Java、IUserService/Impl无CRUD继承、login事务注解保留的边界检查通过。
- 最终Java8离线clean package共664项，0失败/错误、4项原设计跳过；Surefire默认XML独立统计一致。比7AB净增9项，无删除原登录场景；Corretto1.8.0_492、Maven3.9.9和依赖不变，定向不与默认统计叠加冒充覆盖率。
- 十四套显式IT共187项，0失败/错误/跳过：新账号MySQL4、验证码Redis4、登出2、Blog用户4、关注18、资料4、券目录4、分类8、签到7、评论82、投递24、发布5、编辑14、FeedRedis7。自建MySQL9.6.0/Redis8.6.2，合成数据与随机localhost端口；11个记录私有端口独立确认无监听。新IT使用真实UserMapper/SqlSessionTemplate和外层TransactionTemplate，验证phone查询/nullable数据/非缓存、账号与USER角色同事务提交、角色trigger异常回滚两表；不是单独证明生产login代理，后者由Compose补验。SQL NULL icon与原实体默认空串/默认不调用null setter的Mapper对照一致，不改生产映射配置。
- 全新Compose完整73组通过（原70、新3），MySQL8.4.11/Redis6.2.24，result.json另行核对组数/版本。自有手机号/大ID账号验证原三角色登录、数字ID/三字段字符串session白名单、会话TTL、已有账号不重建或修改；停用账号的code先消费再401，无角色ACTIVE账号登录成功后原filter拒绝/清会话。仅隐藏phone列时原code已消费、lookup通用500，finally恢复后原账号可再登录。仅对自有新手机号的角色INSERT触发SIGNAL，真实login代理使账号与角色回滚，Redis码仍已消费；finally删除trigger，重新准备合成code后只建一个ACTIVE账号和USER角色，昵称前缀/长度和后续认证可用。全部旧场景、主机jar/prod缺配置拒绝保留通过；精确Compose项目容器/网络/卷/应用镜像独立读回均空。
- 最终jar共232个应用class/资源，与target/classes逐文件一致，HTTP前后有序内容指纹均为SHA-256 `ed63fb9970f8fb9a1678133be86a8b69cf58c265d1c2919fc4099c52aa375475`。登录方法体仅两处账号存取表达式替换，其余逐字节一致；失败辅助方法不变。Controller/机器API目录、Mapper/Entity/DTO/utils、security、resources和POM无差异。原教学目录HEAD与两个未跟踪条目读回未改。
- 7份相关Markdown的383个相对文件链接、Python AST、机器JSON、diff --check通过；17个变动路径选定私钥/AWS/GitHub token/个人绝对路径模式无命中，不是完整安全审计。运行日志和合成凭据保留私有临时目录，不提交；本轮未为迁移修改原数据/权限规则。

## 部署与回退

clean构建完整jar后替换；无DDL或会话格式迁移，回退7AB无需数据回退，但不支持任意更早版本或旧内部CRUD继承的Java二进制兼容。

只使用独立副本、临时公开无凭据Maven配置、自建随机localhost端口组件和全新Compose合成数据；不读个人.env/凭据、不接共享服务、不发真实短信、不部署生产。HTTP故障仅改测试自有phone列和专属角色trigger，并在finally恢复/删除，不改生产schema。原教学目录保留。验证、scoped commit、新未参与实现Luna只读审查实际SHA、普通push与fetch/readback后停止，最终状态在交付对话报告；不等于生产、HA、容量或个人掌握认证。
