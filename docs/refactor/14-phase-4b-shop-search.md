# Phase 4B 校园门店搜索与引擎决策

## 范围与结论

基线 `bb79f91` 已完成门店详情缓存治理。本阶段新增 `GET /shop/search`，支持名称关键词、类别、价格区间、最低评分与距离筛选/排序，并交付 V005 候选索引。保留旧 `/shop/of/name`、`/shop/of/type` 及详情读写行为，不清理 Redis key、不批量迁移历史数据，不进入 Phase 5。

采用 MySQL 基线，暂不引入 Elasticsearch。理由与重新选型条件见 [ADR 0002](../adr/0002-shop-search-engine.md)：当前没有数据规模、目标负载或分词相关性评测证明需要 ES；引入时必须同时交付初始化、可靠增量同步与重建/回滚，不能仅安装依赖。尚未进行实际压测。

## HTTP 合同

匿名 GET 可访问；携带 token 时继续经过既有认证过滤器。新增静态路由不会被 `/shop/{id}` 当成门店 ID。仅以下字段可绑定，未知/内部字段被忽略，不影响查询投影。

| 参数 | 规则与语义 |
| --- | --- |
| `keyword` | 原始长度最多80个 UTF-16 code unit，trim 后空串等于无过滤；仅匹配 `name` 子串，遵循现有 `utf8mb4_general_ci`，不是分词、纠错或全文相关性搜索 |
| `typeId` | 正 Long；无该类别时正常返回空页 |
| `minPrice` / `maxPrice` | 非负 Long，下限不得大于上限；单位沿用现有 `avg_price`，不做币种/分元换算 |
| `minScore` | 整数 0..50；沿用五分评分乘10存储语义 |
| `x` / `y` | 成对且有限的经度/纬度，分别 [-180,180] / [-90,90]；NaN/Infinity 拒绝 |
| `radiusMeters` | 可选整数1..50000，必须提供坐标；球面直线距离小于等于半径，不是导航距离 |
| `sort` | `id` 默认；`price_asc`、`price_desc`、`score_desc`、`distance`；距离排序必须提供坐标，未知值拒绝 |
| `page` / `size` | page 1..500 默认1；size 1..50 默认10，最大 offset 24950 |

参数格式或组合错误返回 HTTP 400 `VALIDATION_FAILED`。Controller binder 白名单与 DTO 校验同时生效；Service 再次验证内部调用。查询投影是不可变内部对象，不允许 HTTP 绑定 `likeKeyword`、`offset` 或 `merchantId` 等字段。

成功使用既有 Result 包装：`success=true, data={items,total,page,size,sort,hasNext}`。items 仅含字符串 `id/typeId`、`name/images/area/address/avgPrice/score/openHours/distanceMeters`，不包含 merchantId、原始坐标、销量/评论计数或 SQL 内部字段。没有坐标时 distance 为 NULL，应用的 non_null 序列化会省略空字段。评分/价格可为 NULL；精确匹配这些数值条件时 NULL 不满足条件。

空结果返回 items 空数组、total 0 和实际请求分页信息。超出末页但在合法页深内，items 为空而 total 仍为真实总数。`hasNext = (long) page * size < total`。不使用结果条数猜下一页。

SQL 数据访问故障在服务内转为 `SHOP_STATE_UNAVAILABLE` 503；事务开始/结束发生在服务方法外，Controller 局部处理 transaction/data-access 异常，输出同一安全错误码。失败不伪装为无门店，不返回数据库异常文本。这不修改其他 Controller 的异常策略。

## SQL、事务与数据新鲜度

完整调用链：`ShopSearchController → ShopSearchService → ShopSearchMapper.xml → tb_shop`。所有用户值使用 `#{}`；LIKE 使用显式 `ESCAPE '!'`，依次转义 `!/%/_`，反斜杠作为普通文字。排序仅输出固定 `<choose>` 分支，没有 `${}`；投影列显式列举，不 SELECT *。

COUNT/SELECT 共用相同过滤片段。SQL 两个 mapped statement 均配置 `timeout="5"`，静态测试读取实际 MyBatis MappedStatement 验证，不假定 Java 注解会覆盖 XML。该5秒是每条查询的 statement timeout，不包含连接池等待、事务或整个 HTTP 时长，不是服务 SLA。

`@Transactional(readOnly=true,isolation=REPEATABLE_READ)` 保持单请求 COUNT 与本页在同一 InnoDB 一致性快照。COUNT 为0跳过页查询。排序始终追加 `id ASC`，价格/评分 NULL 放最后。单次请求一致不意味着多次分页共享冻结快照，并发修改后新的分页请求可能移动。

距离使用 Haversine，地球平均半径 6371008.8 米，计算值夹紧 [0,1]。距离排序/半径筛选排除 NULL 或范围不合法的存储坐标；提供坐标但非距离筛选/排序时仍保留这些门店，distance 为 NULL。不依赖 Redis GEO，不增加 ES 或搜索缓存，提交后的新搜索事务直接读数据库事实。

原坐标列是 UNSIGNED，本阶段只是允许查询源点使用负坐标，不迁移存储写模型。旧名称列表、分类/GEO列表仍沿用原分页与数据读取逻辑，不能声称新搜索已修复它们的旧约束或 GEO 同步。详情缓存也保留 Phase 4A 的最终一致性窗口，搜索与详情可能短暂看到不同版本。

## V005 迁移与部署前检查

在现有表和 V001..V004 之后执行：

```bash
mysql -u "$DB_USERNAME" -p hmdp < src/main/resources/db/migration/V005__add_shop_search_indexes.sql
```

增加 `idx_shop_search_type_price(type_id,avg_price,id)` 与 `idx_shop_search_score(score,id)`。通过 INFORMATION_SCHEMA/PREPARE 判断同名索引，正向与重复执行保留行数据；同名但结构错误不会自动修正，上线前应检查实际列顺序。DDL 可能阻塞/增加磁盘与写入成本，需要备份和受控窗口；失败后先检查结构再重试，不盲目删改索引。

这些是候选索引，不保证特定访问路径。前导 `%关键词%`、NULL-last、混合排序与距离表达式仍可能扫描或 filesort，COUNT 也可能昂贵。当前没有真实业务数据上的 EXPLAIN/压测优化证据，不宣称索引提升或数据库可承受某个规模。

## 实际验证

2026-10-09 使用项目要求的 JDK 1.8.0_492，命令行 Surefire 3.1.2，POM 不变。默认 suite 不连接外部服务。MySQL/Redis IT 自建随机 localhost 端口、私有 datadir/进程与合成数据，不读取开发 .env 或连接共享数据库。

默认 clean 回归：205项，0失败/错误，4项手工外部服务用例按设计跳过。本阶段新增43项：Service7、SQL合同3、迁移静态1、HTTP合同32。HTTP使用真实 Spring Security过滤链与MockMvc，但业务依赖是mock，不是部署网络验收。

搜索 MySQL IT10项定向通过：真实 XML Mapper/Validator/事务拦截；中文组合筛选与64位字符串ID；字面 %/_/!/反斜杠/SQL注入文本；稳定排序与NULL-last；分页/空页/提交后可见；半径附近、零距离、反日界线、极点、对跖点和坏存储坐标；V005正向重复执行及实际索引列顺序；latch控制的两个连接并发提交验证单请求快照；真实临时缺表映射503并恢复。测试不是完整HTTP→MySQL网络链路，不安装method-security；HTTP合同另测。

完整隔离集成回归：82项，0失败/错误/跳过；其中缓存Redis13、订单Redis35、缓存MySQL/Redis9、订单MySQL/Redis15、新搜索MySQL10。本轮重新运行全部七个IT类，没有沿用上一阶段72项通过作为本轮结论。

首次定向构建暴露HTTP测试用了Java11 `String.repeat`，改用Java8测试数据构造；第二次HTTP测试一项要求 `data:null`，实际non_null合同省略该字段，核对响应后修正断言。该轮MySQL10项均通过。随后默认clean回归205项通过，保留首次失败记录，不计为最终成功证据。

```bash
./mvnw -Dmaven-surefire-plugin.version=3.1.2 clean test
./mvnw -Dmaven-surefire-plugin.version=3.1.2 \
  -Dtest=ShopCacheRedisIT,OrderCancellationRedisIT,OrderStreamRedisIT,FlashSaleRedisScriptIT,ShopCacheMySqlRedisIT,OrderLifecycleMySqlRedisIT,ShopSearchMySqlIT test
```

MySQL实测9.6.0，Redis实测8.6.2；不能据此宣称目标MySQL8/Redis6、完整历史迁移、真实网络部署、持久化/failover或生产安全治理已通过。尚未进行实际压测，没有QPS、P95/P99、相关性分数或性能提升数字。

重要设计与面试追问见 [搜索基线学习说明](../learning/shop-search-baseline.md)。
