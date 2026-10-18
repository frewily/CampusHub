# Phase 7S：门店分类查询业务切片

日期：2026-10-18。基线 `dc0af44f79803b614bf78dab159bb91a1164935a`。沿7R与既定模块化单体方向继续小步分包，只提取已有分类读取用例，不新增产品功能。

## 契约与范围

旧controller.ShopTypeController→IShopTypeService/ShopTypeServiceImpl→Redis/通用MyBatis服务，改为merchant.api.ShopTypeController→application.ShopTypeQueryService→ShopTypeCache/ShopTypeRepository→infrastructure.RedisShopTypeCache/MyBatisShopTypeRepository，config负责装配。应用编排无Spring/MyBatis/Hutool/ThreadLocal依赖；仍复用旧ShopType Entity和Mapper，未改变扫描/别名配置或宣称全部模型隔离。

- 仍为公开GET `/shop-type/list`、handler queryTypeList。无角色/商户成员门槛；有效USER/MERCHANT/ADMIN可读，携带失效/停用/无有效角色token仍按原公开读取处理，不把参与活动的策略搬过来。SecurityConfig与认证过滤器不改。
- 成功200 Result.ok(List<ShopType>)，Entity响应、数字ID与@JsonIgnore时间字段保留；空库为空数组。SQL只按sort升序，NULL排序沿MySQL，相同sort无额外次序保证。缓存命中不重新排序。
- 原 `cache:shop:type`，原Hutool JSON；缺失/空/空白才回源，非空白按原解码结果返回，[]是命中。Lookup显式区分是否命中与解码值，避免引入空结果回源。成功回源后写30分钟TTL；命中不续期。
- 服务内缓存读取/JSON/SQL/写缓存异常仍交给原通用500处理，不吞错、降级、返回部分成功或改缓存策略。无锁、抖动、坏缓存自动修复、失效补偿、分页或分类写入口。
- 旧分类Controller和IService接口/实现删除，Git历史可恢复；HTTP兼容不承诺未入库外部Java调用者二进制兼容。应用仍51条映射/14个Controller，简单Controller名不变，机器清单无需改写；源码链接与根包发现检查同步。

## 验证记录

- 定向67项通过，0失败/错误/跳过，含新分类组件IT8及旧安全/签到/目录回归；新增默认检查25项（查询编排8、适配器9、模块边界2、HTTP5、目录1）。不将定向与全量相加当覆盖总数。
- 最终Java8离线 `clean package` 默认统计545项，0失败/错误、4项原设计跳过。Corretto1.8.0_492、Maven3.9.9、Boot2.7.18、MyBatis-Plus3.4.3保持；原有测试方法保留，仅增加目录发现检查。
- 七套显式IT共147项通过，0失败/错误/跳过：分类MySQL/Redis8、签到Redis7、评论MySQL82、投递MySQL24、发布MySQL5、编辑MySQL14、Feed Redis7。本机自建MySQL9.6.0/Redis8.6.2，默认Surefire不发现IT，这147项独立于默认545统计。
- 分类组件验证NULL/sort/tie范围、列名/时间映射、旧JSON命中、表不可用时命中及[]仍成功、空/空白回源、空库写[]、坏JSON不覆盖、30分钟TTL与命中不续期、SQL故障不写成功缓存。mock覆盖缓存读/解码/SQL/写失败不被吞掉；不把mock替代真实依赖。

整合中按系统化调试核对真实依赖API：新IT错引GlobalConfig包、调用不存在的factory destroy与Redis get，依据当前jar/既有fixture改用正确API，未升级依赖。首次真实IT跨用例复用了raw SqlSession，日志只有首个空查询的实际SQL，四个断言失败；改成与已有项目一致的SqlSessionTemplate操作会话后原断言通过，没有关闭生产缓存或改变查询规则。单元SQL片段只规范化前导空白，仍精确断言ORDER BY sort ASC且无参数，不放宽排序。

首次Compose停在“单空格缓存仍保留”的检查；私有原始CLI日志确认空格存在，但公共文本run工具strip后呈现为空。改用STRLEN=1且BITFIELD GET u8=32精确核对同一个ASCII字节，未改生产或放宽业务断言。该失败项目已清理，按精确标签/镜像名独立复核四类资源无残留。三个IT运行日志记录的16个本机端口亦均无监听。

- 最终jar在全新Compose完整52组通过（原50、新2），MySQL8.4.11/Redis6.2.24。真实HTTP验证匿名及USER/MERCHANT/ADMIN读取、失效/停用/无角色身份仍可公开读、NULL/sort/tie范围、精确Entity字段和数字ID、时间字段不泄露、旧JSON缓存与30分钟TTL。自建分类表临时不可用时，缓存及[]仍成功，命中不续期；坏JSON和SQL失败仅通用500且缓存未被成功值覆盖，表恢复后空白缓存可回源。相同sort不增加ID次序承诺。
- 原签到、评论/回复/审核/审计、动态编辑/撤回、Feed、发布编号、图片、安全、依赖重启、主机jar与prod缺配置拒绝完整回归。最终项目清理后，按精确标签/镜像名独立读回容器、网络、卷与项目镜像均无残留；未清理其他项目。原教学目录HEAD及原未跟踪文件读回不变。
- 25个阶段改动路径（含3个旧源文件移除）的选定高可信私钥/AWS/GitHub token/个人绝对路径模式无命中，不宣称完整安全审计；8份相关Markdown共319个相对文件链接存在性、脚本AST/机器JSON、git diff --check通过。机器清单与基线逐字节一致，仍51条/14个Controller；原测试方法保留。最终jar内8个新模块class与编译结果逐字节一致，三个旧类型均不存在。

以上为选定本机证据，不把mock、组件IT、干净启动或目录一致性当作生产、容量、跨故障HA、浏览器或个人掌握证明。

## 部署与回退

不新增SQL/schema/迁移，仍V001–V014；不移动表、Redis键或清理用户数据。符合此前迁移的7R部署可替换本轮jar，旧缓存可直接读取；仅从7S回退7R不需数据回退。既有withdrawn等旧版本回退限制仍适用，不意味着可回退任意历史应用。

本轮删除/移动Java源文件，须从 `clean package` 生成并替换完整jar；IDE增量运行前也要清理旧编译输出，避免旧Controller class残留造成重复Bean/映射。不能只复制新增class。模块边界测试会拒绝仍可加载旧类型的陈旧构建。

只在独立开发副本、自建服务、随机localhost端口、合成身份与新建Compose项目验证；原教学目录及其未跟踪文件保留。不读个人.env/凭据或连接共享服务，不做生产迁移/部署、容量、浏览器或个人能力认证。个人掌握仍需独立确认。

按实现→验证→scoped commit→新未参与实现Luna独立只读审查实际SHA→普通push→fetch/readback收口，停在本阶段；最终提交/审查/同步以交付对话为准。
