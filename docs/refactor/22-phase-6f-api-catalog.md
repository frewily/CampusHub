# Phase 6F：当前 API 目录与源码漂移检查

日期：2026-10-10。应用基线：`68aa03bd86cb905c4828cd3a6c3b4616197aeca8`。承接 6D 盘点中尚未交付的统一接口目录，只补当前源码事实和回归门禁，不推进新的产品用例或架构。

## 范围与交付

- [目录入口](../api/README.md)：认证、角色、混合响应/ID、错误状态、管理面与图片边界。
- [逐接口说明](../api/endpoints.md)：37 个主源码显式 method/path 映射，参数、资源限制、响应和缺失资源语义，链接 Controller、Service 与请求/响应模型。
- [机器清单](../api/routes.json)：method/path、Controller、handler 与声明的 preAuthorize 原文；空授权声明不表示匿名。
- [ApiInventoryContractTest](../../src/test/java/io/github/frewily/campushub/controller/ApiInventoryContractTest.java)：自动扫描主源码产物中的 Controller，在 standalone Spring MVC 中注册并比较实际映射，排除测试探针；检查清单与 Markdown 表格一致。
- README、迁移计划更新当前入口和剩余范围，旧阶段性能/部署/当时未交付的历史记录保持原样。

主源码共有 12 个 Controller，其中 11 个提供这 37 个显式映射；空的 BlogCommentsController 不提供可调用接口。应用端计数包含两条健康和一条图片读取，另列管理端 GET /actuator/health 与 GET /actuator/prometheus，不计入 37。排除 Spring 隐式 HEAD/OPTIONS、框架 /error 和静态基础设施。

没有 src/main、Java/Lua 业务行为、schema、依赖版本、运行配置、部署配额、测试负载或原始性能证据变化；未安装在线 Swagger UI，不生成未来后台/评论接口。

## 核对中保留的现状

本目录不把遗留行为写成理想设计：

- 分类门店查询两条分支沿用继承的 IService.DEFAULT_BATCH_SIZE；当前 MyBatis-Plus 3.4.3 常量为 1000，其他门店/动态列表为 10、Feed 每批 2。没有顺带调整分页。
- 参与身份允许 USER/MERCHANT 且不含 ADMIN；商户成员关系只在相应管理操作检查，不把管理规则套到活动参与。
- 共同关注的 ID 确实有 Positive；图片限制区分输入/重编码文件大小和像素数，不误写成解码内存上限。
- 部分读取仍直接返回 Entity/数字 ID，部分新模型返回字符串 ID；缺失用户资料与缺失门店/动态/订单不采用相同语义。
- 图片删除仍是受 ADMIN 限制的 GET 写操作；会话采用原始 authorization 头，不是 Bearer/JWT；受理成功不是订单已支付/落库。

## 本轮验证

- Corretto 1.8.0_492 / Maven 3.9.9，项目专用离线依赖缓存，默认 clean package：**259 项测试，0 失败/错误，4 项教学实验按设计跳过**，构建/打包成功。
- 新增 **4 项**契约检查：实际映射与授权声明匹配、逐接口表格匹配、遗漏/新增/重复/改授权声明负向检查、空评论 Controller 边界。定向运行同样 4 项通过。
- 没有启动 Docker、新建服务负载或读取个人环境文件；未重跑真实 DB/Redis IT、Compose/业务故障验收或 6E2 性能。默认套件的现有真实本机 HTTP 测试使用 mock 依赖，不能混同于真实数据库验收。
- Luna 只读预审后澄清图片成功二进制/缺失时 Result 错误 JSON 的区别，以及 source_revision 为人工固定基线而非 HEAD 同步门禁；阶段记录已创建。修正后 4 项定向检查重新通过，157 个相对文件链接存在、围栏配对、37 行表格列宽、敏感信息模式扫描和 git diff --check 通过；链接检查不验证源码行锚点语义。

提交后由独立 Luna 复审，通过后普通推送并读回远端。实际审查与推送结果在本阶段交付对话中报告，不预先写成已经发生。

## 能力边界与剩余范围

漂移测试只验证注册结果、声明的 method/class PreAuthorize 和文档方法/路径集合，不发送请求、不启用实际安全链、不连接 DB/Redis、不启动 worker；不自动验证字段语义、Service/Mapper 成功路径、角色/归属策略执行、条件配置或管理端暴露。当前没有按请求参数/媒体类型分组的重载映射；未来引入时需扩展清单维度。字段/响应事实仍需源码审查，HTTP/真实依赖行为沿用各阶段明确范围的验收，不宣称全部 37 接口完成端到端验收。

source_revision 记录建立目录时的源码基线；只检查格式，不验证它对应当前被测代码，不要求文档提交后与 HEAD 相同。未来行为变化需要人工同步说明/基线，测试通过不能替代该核对。

这是手工维护的当前 API 目录，不是完整 OpenAPI schema/SDK。完整商户/管理后台、评论评价、通知、动态脱离门店关联、业务模块分包及历史迁移/真实短信/生产验收仍未完整交付；不因目录齐全标为完成。

本阶段经验证、提交、独立审查和推送后停下，再由主人确定下一阶段范围。
