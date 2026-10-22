# Phase 7W：公开优惠券目录查询切片

日期：2026-10-22。基线 `8bb0df3aa3af18b851d3b19e3ea3b9358b548d0e`，远端核对一致后继续既定模块化单体分包。

## 范围与兼容边界

旧VoucherController/IVoucherService/VoucherServiceImpl的queryVoucherOfShop移入promotion.api.VoucherQueryController→application.VoucherQueryService→VoucherQueryRepository→infrastructure.MyBatisVoucherQueryRepository，由config装配。纯Java服务不依赖Spring、MyBatis、ThreadLocal、Redis或通用IService，仍复用旧Voucher Entity；Mapper/XML/Entity不搬迁。

- 原GET `/voucher/list/{shopId}`、handler、正Java Long/原校验消息及公开策略不变。匿名、有效各角色及失效/停用/无有效角色身份仍可读，无新增角色注解；认证过滤器可能续期会话，不宣称整个HTTP无Redis写。
- 仍调用原XML：shop_id=#{shopId}且status=1，LEFT JOIN tb_seckill_voucher；只映射原列。不查门店/账号存在，不按时间、type或stock加过滤，不排序/分页/去重/缓存。上架普通券、无库存行、过期/未开始/零库存活动均保留；数字ID/金额/LocalDateTime不改。未选取status/createTime/updateTime及null库存/时间按NON_NULL不输出。
- 无券返回空数组；复用原列表/异常语义，不做null归一化或故障空成功。任一关联SQL表不可用仍原通用500，不写券或库存、不缓存/修复。目录展示不等于参加资格或实时库存保证。
- 原普通券/秒杀券创建的方法体与权限声明不变，FlashSaleRules/Redis发布、准入/异步订单/取消链不改。旧三层只移除该内部Java查询方法，不承诺二进制兼容。51条应用映射/16个Controller（旧券Controller2 POST、新查询Controller1 GET），机器清单仅改GET处理器归属，V001–V014不变。

## 验证记录

- 73项定向检查通过，0失败/错误/跳过：新编排3、Mapper适配器2、模块边界2、查询HTTP6，原安全HTTP21、API模型HTTP31、接口目录8。保留原写接口/权限断言，安全fixture加入新读取Controller，不把mock响应当真实SQL映射证据。
- 最终Java8离线clean package共601项，0失败/错误、4项原设计跳过，比7V净增13；Corretto1.8.0_492、Maven3.9.9与既有依赖版本不变，最终Surefire XML另行核对一致。
- 九套显式IT共167项通过，0失败/错误/跳过：新券目录MySQL4、关注读写/关注者16、分类8、签到7、评论82、投递24、发布5、编辑14、Feed Redis7。自建MySQL9.6.0/Redis8.6.2、随机localhost端口与合成数据；新IT实际加载原VoucherMapper.xml并使用SqlSessionTemplate，验证列/大整数/LEFT JOIN/上架筛选、无门店表也可读、无时间/库存筛选、空与状态变化不缓存、任一表故障不吞错且恢复可读、读取不改所有事实列。167项不属默认Surefire发现范围，定向/默认/IT统计不累加冒充覆盖总数。
- 全新Compose完整60组通过（原58、新2），MySQL8.4.11/Redis6.2.24。新增真实匿名HTTP、正Long/溢出400、数字大ID/金额、字段白名单/LocalDateTime、普通券与缺库存行、零库存过期/未来活动、不存在门店无额外验证、上架变化实时读、两张SQL表故障通用500且数据不变/finally恢复。没有排序、实时库存、浏览器大整数无损或跨表强一致保证。原关注/发布/评论/审核/Feed/图片/安全/重启/主机jar/prod缺配置拒绝等完整验收保留。
- 首轮73项定向有1项新HTTP期望失败：LocalDateTime.toString省略零秒，而既有Jackson输出包含秒。仅将新测试锁定为原JSON格式的精确字符串，73项重跑通过，不改生产序列化。首轮Compose在测试自己更新券状态后仍跨UPDATE比较原始全行快照；真实schema的ON UPDATE时间戳会改变，故不是只读请求前后同一状态。组件用固定旧时间戳对照确认SELECT不变、夹具UPDATE改变；脚本保留完整全列断言，分别以各夹具状态作前后基线，再全新项目60组通过。不删旧断言、不放宽生产规则。
- HTTP使用并行验证构建；最终clean package的全部212个应用class/资源条目与HTTP验收包有序内容指纹一致（SHA-256 `e296129be14d4c83e0cd0c7e6ee4d7d11b206474ed27d58ebc483834cae10147`），并与target/classes逐文件一致。两次精确Compose测试项目清理后容器、网络、卷和应用镜像独立读回均空，本轮15个记录私有IT端口均无监听；原教学目录HEAD与未跟踪文件未变。
- 原券Controller/Service的两个写方法体同基线逐字节一致；XML/Mapper/Entity、V001–V014和选定限量活动/订单生产链无差异。机器清单51条method/path/guard不变，仅一个GET改Controller归属；当前16个Controller。7份相关Markdown的334个相对文件链接、Python AST、机器JSON、git diff --check及变更路径选定私钥/凭据/个人绝对路径模式检查通过；不是完整安全审计。

## 部署与回退

仅用独立开发副本、临时无凭据Maven配置/缓存、自建随机localhost MySQL进程及全新Compose合成环境，不读个人.env/凭据、不连共享服务或生产部署。原教学目录与未跟踪文件保留，临时日志/依赖不提交。

需要clean构建完整jar以避免旧Controller方法残留；满足既有迁移前提时回退7V不需数据回退，不能据此任意回退更早版本。不新增schema、数据重建或Redis格式变化。

按实现→验证→scoped commit→新未参与实现Luna独立只读审查实际SHA→普通push→fetch/readback后停止，最终状态在交付对话报告。选定本机验证不是生产、容量、HA、浏览器或个人掌握认证；个人掌握仍需独立确认。
