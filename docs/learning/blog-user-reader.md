# 动态展示资料：内容读取不必依赖登录服务

## 本阶段实际边界

BlogServiceImpl原来依赖IUserService，只为读作者和点赞人。现在依赖content.application.BlogUserReader：findAuthor(Long)返回UserDTO或null，findLikers(List<Long>)返回UserDTO列表；基础设施适配器通过旧UserMapper查询。接口不包含登录/Redis/Entity/框架类型，但仍复用旧DTO/Mapper，不宣称整个content或identity已模块化。

## 两种顺序不能混用

作者使用selectById，展示姓名/图标来自数据库，不是当前会话。详情、热门与Feed复用该路径；原“作者缺失后读取字段会失败”的行为保留，不夹带匿名作者占位策略。若产品要支持注销用户的历史内容，需另定保留、展示和删除规则。

点赞人先由原服务读取blog:liked:{postId}的ZRANGE 0..4，按原顺序将member转为Long，再由适配器执行绑定IN和原ORDER BY FIELD(id,...)。只有解析后的Long用于FIELD拼接，不把外部原始字符串直接拼SQL。资料缺失只减少返回数量，不取第六名补齐；停用目标不新过滤。与共同关注不承诺顺序的批量查询不同，这里必须保留Redis选择的顺序，不能机械复用7Y无序适配器。

空/null ZSet提前返回空列表，不调用用户读取端口；非法member、Redis/SQL故障仍失败，不静默空成功。服务仍负责原动态可见性检查、点赞标记和Feed游标；本次不修复SQL/Redis一致性、分页快照或隐私策略。

## 验证时关注什么

mock验证端口/SQL参数及跳过行为；私有MySQL验证FIELD排序、缺失/停用行和真实故障；全新Compose验证实际认证、DTO序列化、热门/详情/Feed展示和故障恢复。只读资料不能改变用户/动态事实或投影；认证过滤器续期会话是既有行为，不把整个HTTP请求说成绝无Redis写入。

clean构建完整jar后替换，无新DDL/Redis格式，回退7Y无数据回退；不支持任意回退更早版本。选定本机测试不等于生产验收或个人掌握。范围及实际结果见[7Z记录](../refactor/48-phase-7z-blog-user-reader.md)。
