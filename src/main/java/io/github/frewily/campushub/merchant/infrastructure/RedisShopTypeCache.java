package io.github.frewily.campushub.merchant.infrastructure;

import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONUtil;
import io.github.frewily.campushub.entity.ShopType;
import io.github.frewily.campushub.merchant.application.ShopTypeCache;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.concurrent.TimeUnit;

import static io.github.frewily.campushub.utils.RedisConstants.CACHE_SHOP_KEY;
import static io.github.frewily.campushub.utils.RedisConstants.CACHE_SHOP_TTL;

@Component
public class RedisShopTypeCache implements ShopTypeCache {
    private final StringRedisTemplate redis;

    public RedisShopTypeCache(StringRedisTemplate redis) {
        this.redis = redis;
    }

    @Override
    public Lookup read() {
        String json = redis.opsForValue().get(CACHE_SHOP_KEY + "type");
        return StrUtil.isNotBlank(json) ? Lookup.hit(JSONUtil.toList(json, ShopType.class)) : Lookup.miss();
    }

    @Override
    public void write(List<ShopType> types) {
        redis.opsForValue().set(CACHE_SHOP_KEY + "type", JSONUtil.toJsonStr(types), CACHE_SHOP_TTL, TimeUnit.MINUTES);
    }
}
