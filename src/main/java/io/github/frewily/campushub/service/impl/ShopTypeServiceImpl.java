package io.github.frewily.campushub.service.impl;

import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONUtil;
import io.github.frewily.campushub.dto.Result;
import io.github.frewily.campushub.entity.ShopType;
import io.github.frewily.campushub.mapper.ShopTypeMapper;
import io.github.frewily.campushub.service.IShopTypeService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import javax.annotation.Resource;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static io.github.frewily.campushub.utils.RedisConstants.CACHE_SHOP_KEY;
import static io.github.frewily.campushub.utils.RedisConstants.CACHE_SHOP_TTL;

@Service
public class ShopTypeServiceImpl extends ServiceImpl<ShopTypeMapper, ShopType> implements IShopTypeService {

    @Resource
    StringRedisTemplate stringRedisTemplate;

    @Override
    public List<ShopType> queryList() {
        String key = CACHE_SHOP_KEY + "type";
        String shopTypeJson = stringRedisTemplate.opsForValue().get(key);
        if(StrUtil.isNotBlank(shopTypeJson)){
            return JSONUtil.toList(shopTypeJson, ShopType.class);
            /**
             * JSONUtil.toList:将JSON字符串反序列化为List集合
             * ShopType.class：指定List中每个元素的类型是 ShopType 对象
             * 序列化（Serialization）：将对象转换为可存储或传输的格式
             * 用途：
             * 存入Redis、数据库
             * 网络传输（HTTP请求）
             * 保存到文件
             * <p>
             * 反序列化（Deserialization）：将存储或传输的格式转换为对象
             * 用途：
             *从Redis读取后还原对象
             * 接收HTTP请求参数
             * 从文件读取数据
             * <p>
             * 简单理解：
             * 序列化 = 打包（对象→字符串）
             * 反序列化 = 拆包（字符串→对象）
             */
        }
        List<ShopType> shopTypeList = query().orderByAsc("sort").list();
        stringRedisTemplate.opsForValue().set(key, JSONUtil.toJsonStr(shopTypeList), CACHE_SHOP_TTL, TimeUnit.MINUTES);
        return shopTypeList;
    }
}
