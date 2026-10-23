# Phase 7X：用户资料读取业务切片

日期：2026-10-23。基线 `b6376f3d0919f5d99a711eeb051b0200fd195dd8`。远端核对一致后继续既定模块化单体方向，不新增产品能力。

## 范围与兼容边界

`/user/me`、`/user/info/{id}`、`/user/{id}`迁入identity.api.UserProfileController，保留handler名和原认证。me仍直接Result.ok(UserHolder.getUser())，不调用资料服务；两个按ID查询经UserProfileQueryService→UserProfileReader→MyBatisUserProfileReader，以原Mapper.selectById和BeanUtil/ApiModelMapper生成旧DTO。

- 三个GET均需有效认证，无额外角色注解；USER/MERCHANT/ADMIN可读，匿名/失效/停用/无有效角色身份401，两个路径仍正Java Long/原校验消息。me忽略外部userId，返回会话昵称/图标而不强制刷新数据库；认证过滤器依旧可查账号与角色并续期会话。
- 按ID简档仍只有UserDTO三字段，详细资料仍UserInfoResponse九字段；ID为数字，生日LocalDate、gender/level为Boolean，NON_NULL规则不变，不返回密码、手机号、状态或创建/更新时间。保留原实体不被映射修改的边界。
- 缺失记录均成功不带data，不是404；两次独立查询不join、不增加目标存在/归属/停用检查，不过滤孤立资料行；SQL异常通用500，不吞错、补查、缓存或修复。不把保留的旧读取策略说成生产隐私保障。
- UserController只留三个原POST；验证码/登录/登出方法体及UserServiceImpl/认证生产链不变。无其他调用的IUserInfoService/UserInfoServiceImpl删除，但IUserService仍供认证/Blog/social；Entity/Mapper/DTO暂留旧包，不是完整identity迁移。不承诺旧内部Java类型二进制兼容。
- 51条映射/17个Controller，机器清单仅改三个GET归属，V001–V014不变，无新schema、资料写接口、隐私策略或Redis格式。

## 验证记录

- 77项定向检查通过，0失败/错误/跳过：编排3、适配器4、模块边界2、认证/资料HTTP27、API模型32、目录9。原实体时间戳不修改的断言随映射迁到适配器单测；HTTP保留并加强三字段/九字段白名单，旧写入/登录断言不删。
- 最终Java8离线clean package共618项，0失败/错误、4项原设计跳过，比7W净增17；Corretto1.8.0_492、Maven3.9.9及既有依赖不变，最终Surefire XML另行读回一致。
- 十套显式IT共171项通过，0失败/错误/跳过：新资料MySQL4、券目录4、关注读写/关注者16、分类8、签到7、评论82、投递24、发布5、编辑14、Feed Redis7。自建MySQL9.6.0/Redis8.6.2、随机localhost端口与合成数据。新IT使用真实Mapper/SqlSessionTemplate和DTO映射，验证数字大ID、停用目标、九字段/LocalDate/Boolean、孤立资料、两表独立查询/故障传播、缺失null和状态变动不缓存；全事实行及时间戳不被查询改写。171不属默认Surefire发现范围，定向/默认/IT统计不叠加冒充覆盖总数。
- 全新Compose完整62组通过（原60、新2），MySQL8.4.11/Redis6.2.24。新增真实三角色、认证失效/停用/无角色401、正Long/溢出400、外部userId不替代me身份、会话昵称与SQL昵称分离、跨用户/停用目标/孤立资料及缺失无data、DTO数字ID/生日/Boolean和字段白名单。故障只临时改资料查询所需phone列，保留认证id/status与角色表；独立info仍可读，再临时隐藏资料表，两种资料查询通用500而me仍读会话。两处schema均finally恢复，事实快照不变，恢复后读取正常。原关注/券目录/发布/评论/审核/Feed/图片/安全/重启/主机jar/prod缺配置拒绝等完整验收保留。
- 初次Compose在创建测试资源之前因Docker未运行退出，保留启动前失败记录；主人启动Docker后全新项目通过。首次定向编译发现夹具遗漏UserController局部变量和Mockito never导入，补齐原登录Controller与新资料Controller共存，未删除旧写入场景。随后77项中1项新生日断言失败：standalone MockMvc默认输出日期数组，而Boot场景输出ISO字符串；仅对齐该测试夹具的日期序列化设置，不改生产配置、DTO或精确日期断言，API模型32项对照及77项定向重跑通过。
- HTTP使用并行验证构建；最终clean package的215个应用class/资源条目与HTTP验收包有序内容指纹一致（SHA-256 `61dbd9ad05e812ff916a61e185587ebf62900a6ae8014cfca6f95c80ec240a48`），并与target/classes逐文件一致，两个删除的旧服务类型在jar中不存在。精确Compose项目的容器、网络、卷和应用镜像独立读回均空，本轮16个记录私有IT端口无监听；原教学目录HEAD与未跟踪文件未改。
- 原UserController三个POST方法体同基线逐字节一致；IUserService/UserServiceImpl、DTO/映射工具、认证过滤器/SecurityConfig、V001–V014无差异。机器清单51条method/path/guard不变，仅三个GET归属变化，当前17个Controller。7份相关Markdown的343个相对文件链接、Python AST、机器JSON、git diff --check和变更路径选定私钥/凭据/个人绝对路径模式检查通过；不是完整安全审计。

## 部署与回退

仅用独立开发副本、临时无凭据Maven配置/缓存、自建随机localhost MySQL进程及全新Compose合成环境，不读个人.env/凭据、不连共享服务或生产部署。原教学目录及未跟踪文件保留，临时日志/依赖不提交。

需clean构建完整jar避免旧Controller方法或删除的服务类残留；满足既有迁移前提时回退7W不需数据回退，不能任意回退更早版本。不新增DDL或用户数据重建。

按实现→验证→scoped commit→新未参与实现Luna独立只读审查实际SHA→普通push→fetch/readback后停止，最终状态在交付对话报告。选定本机验证不代表生产、容量、HA、浏览器或个人掌握认证；个人掌握仍需独立确认。
