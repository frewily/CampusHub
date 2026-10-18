# 门店分类：分包不等于改变缓存策略

实际开发照着走：列旧HTTP/缓存/SQL契约→把查询编排与Redis JSON、MyBatis细节拆开→固定命中/空命中/失败测试→真实依赖验证→最终jar验证公开读取及旧缓存。继续条件是原契约可核对，且Controller不再依赖通用CRUD接口，不是仅把类搬到新目录。

可复制Prompt：只提取门店分类查询到merchant，保留GET /shop-type/list、Entity序列化、sort排序、cache:shop:type及30分钟TTL；不要顺带新增后台、缓存降级或DTO。编写端口编排、真实MyBatis/Redis与HTTP测试，区分缓存miss和[]命中，核对扫描、旧数据及异常兼容。

## 边界为什么这样选

旧ShopTypeServiceImpl既继承通用MyBatis-Plus Service，又处理缓存字符串和JSON。新ShopTypeQueryService只负责“先读缓存，miss再读数据库并写回”，依赖ShopTypeRepository和ShopTypeCache两个端口。RedisShopTypeCache拥有Hutool解码、原键与TTL；MyBatisShopTypeRepository拥有原Mapper与只按sort排序的查询。端口对应真实外部依赖，不给每个类套一层没有职责的接口。

缓存Lookup把是否命中与解码值分开，避免把[]或解码null悄悄变成miss。命中不刷新TTL、不重新排序。SQL相同sort没有稳定次序保证，不能用测试中偶然的ID顺序增加新承诺。

ShopType Entity/Mapper暂留旧包，HTTP仍直接返回Entity（时间字段JsonIgnore、ID数字）。因此这里只隔离了用例编排，不宣称DTO/领域/持久化模型都已分离，也不改全局Mapper扫描或类型别名。内部Java旧服务类型被移除，HTTP兼容不代表外部Java二进制调用者兼容。

涉及删除/移动源文件时用clean构建完整jar；IDE也须清理旧输出，防止增量编译留下旧Controller class。不要靠修改扫描或覆盖Bean来掩盖陈旧构建。

## 这轮故意不做的事

原缓存只有30分钟TTL；没有命中续期、锁、抖动、坏JSON自动清理、失败回源或失效补偿。本轮保持这一事实，不借重构顺带改变错误语义或宣称分类缓存已有门店详情缓存的全部治理能力。GET公开，不因携带失效token就变成401；身份过滤器仍按原规则处理失效身份。

## 验证与调试注意

mock能验证miss/hit时调用哪些端口，不能证明SQL映射和Redis字节兼容。真实组件IT应按生产使用SqlSessionTemplate管理操作会话；跨测试复用raw SqlSession会让一级缓存遮蔽JdbcTemplate写入和表故障。夹具还必须按仓库实际版本核对GlobalConfig包和生命周期API，不能凭记忆升级依赖或调用不存在的方法。

最终Compose只操纵本次新建项目中的合成表/键；表故障在finally恢复，结束清理自有资源。选定本机测试不是容量或生产验收，个人掌握仍需独立确认。
