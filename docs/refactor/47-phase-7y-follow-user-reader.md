# Phase 7Y：共同关注用户资料读取适配器

日期：2026-10-23。基线 `bffdbe796ac1ca647d42ed37da86adc9beefc8c6`，开始前fetch确认本地与远端main一致、工作树干净。

## 范围与兼容

仅把LegacyFollowUserReader替换为MyBatisFollowUserReader(UserMapper)，实现原FollowUserReader.findByIds。核对实际MyBatis-Plus 3.4.3 IService.listByIds字节码，其本来直接调用getBaseMapper().selectBatchIds；新适配器同样查询并使用原BeanUtil映射UserDTO，不加事务、排序、状态过滤、缓存、回退或额外SQL。移除social对旧IUserService的依赖；UserMapper/Entity/DTO仍暂留旧包，不宣称完整模块隔离。

共同关注仍先读两个follows:{id} ZSet求交集，无交集不查用户，有交集转Long后批量读。缺失行省略，DISABLED目标仍可展示，原数字ID、id/nickName/icon白名单与null省略规则保留。适配器保持Mapper返回行顺序，但SQL未ORDER BY，不承诺输入或固定展示顺序。SQL/Redis/非法member异常仍通用500，不转空成功。资料读取不改事实行/时间戳、关系或投影；认证原有会话续期不变。

Controller、应用编排、关注写入、Blog/Feed、登录/验证码/登出、SecurityConfig、DTO/Mapper与V001–V014不改。仍51条应用映射/17个Controller，routes.json不变；无新业务/隐私策略或索引。

## 本轮验证

- 最终Java8离线clean package：619项，0失败/错误，4项原设计跳过；Surefire XML另行统计一致。Corretto1.8.0_492、Maven/依赖不变，新适配器异常单测使默认数量比7X净增1。原回归断言保留，批量夹具特意让Mapper顺序不同于输入，验证不被重排、缺失省略、数字大ID、DISABLED和三字段白名单。
- 显式组件与定向检查共204项，0失败/错误/跳过：十套IT共173项（共同关注18、资料4、券目录4、分类8、签到7、评论82、Feed投递24、发布5、编辑14、FeedRedis7）；另31项为关注适配器12、模块边界2、编排8、HTTP契约9。IT用真实Mapper/SqlSessionTemplate、自建MySQL9.6.0/Redis8.6.2与合成数据，新覆盖停用大ID和用户表故障传播/恢复，关系行与投影不变；日志显式记录的7个私有端口另行确认无监听。不把204、173、默认619相加冒充去重覆盖数。
- 初次完整HTTP验收在新增场景的投影精确比较失败。系统化调试对照脚本、FollowCommandService和Redis适配器，私有日志确认末尾原重复关注已刷新shared成员分数，而新增场景误用刷新前快照；只在新场景开始重新取快照，保留精确成员/分数及SQL事实比较、原重复关注与全部旧验收，不改生产代码。原失败日志保留，所属测试项目已清理；最终另建全新项目重跑通过。
- 全新Compose完整63组通过（原62、新1），实测MySQL8.4.11/Redis6.2.24。保留三角色/认证/参数/跨用户交集/缺失与停用资料/数字ID白名单等原场景，新增只隐藏phone列的批量资料故障：认证id/status/角色仍可用，有交集通用500，无交集正常空列表、是否关注仍读关系事实；finally恢复列后资料读取恢复，完整两用户事实行/时间戳、关系行和ZSet成员/分数精确不变。全部原发布、评论、审核、Feed、图片、重启、主机jar与prod缺配置拒绝场景仍通过；result.json另行核对63组。成功与失败两个精确测试项目的容器/网络/卷/应用镜像已独立读回为空，不操作共享资源。
- 最终jar的215个应用class/资源条目与target/classes逐文件一致，原Legacy类型不存在，新MyBatis类型存在；两次clean package内容指纹一致（SHA-256 `09b691f735cd3ba0c16c0f27fc817d070c61e036c85b734a37e9fb08a9b999b4`），HTTP验收使用最终包。6份相关Markdown的314个相对文件链接、Python AST、routes JSON、diff --check通过；变更文件选定私钥/AWS/GitHub token/个人绝对路径模式无命中，不是完整安全审计。旧controller/service/security、social应用编排、resources与机器路由清单对基线无差异，原教学目录HEAD及两个未跟踪条目未改。

## 回退与证据边界

完整clean构建替换jar，防止删除的Legacy组件class残留导致重复Bean。无DDL或数据格式变化，回退7X无需数据回退；不承诺删除的内部类型二进制兼容，不能由此绕过更早阶段回退限制。

仅使用既有独立开发副本、临时无凭据Maven配置/缓存、自建localhost随机端口进程与全新Docker Compose项目的合成数据。不读个人.env/凭据、不连接共享服务或生产部署，原教学目录保持不变。验证、scoped commit、新未参与实现Luna独立只读审查实际SHA、普通推送及fetch/readback后停止；最终同步状态在交付对话报告。本机证据不等于生产、HA、容量或个人掌握认证。
