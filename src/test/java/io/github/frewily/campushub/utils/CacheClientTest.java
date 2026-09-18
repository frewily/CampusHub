package io.github.frewily.campushub.utils;

import cn.hutool.json.JSONUtil;
import io.github.frewily.campushub.entity.Shop;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentMatcher;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.time.LocalDateTime;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class CacheClientTest {

    @Mock
    private StringRedisTemplate stringRedisTemplate;
    @Mock
    private ValueOperations<String, String> valueOperations;

    private CacheClient cacheClient;

    @BeforeEach
    void setUp() {
        when(stringRedisTemplate.opsForValue()).thenReturn(valueOperations);
        cacheClient = new CacheClient(stringRedisTemplate);
    }

    @Test
    void shouldLoadAndWrapDataWhenLogicalExpireCacheIsCold() {
        when(valueOperations.get("cache:shop:1")).thenReturn(null);
        Shop databaseShop = new Shop().setId(1L).setName("Campus cafe");

        Shop result = cacheClient.queryWithLogicalExpire(
                "cache:shop:", 1L, Shop.class, ignored -> databaseShop, 30L, TimeUnit.MINUTES);

        assertEquals(databaseShop, result);
        verify(valueOperations).set(eq("cache:shop:1"), argThat(logicalExpireValueWithName("Campus cafe")));
    }

    @Test
    void shouldKeepLogicalExpireEnvelopeWhenExpiredEntryIsRebuilt() {
        RedisData stale = new RedisData();
        stale.setData(new Shop().setId(1L).setName("stale"));
        stale.setExpireTime(LocalDateTime.now().minusMinutes(1));
        when(valueOperations.get("cache:shop:1")).thenReturn(JSONUtil.toJsonStr(stale));
        when(valueOperations.setIfAbsent("lock:shop:1", "1", 10L, TimeUnit.SECONDS)).thenReturn(true);
        Function<Long, Shop> fallback = ignored -> new Shop().setId(1L).setName("fresh");

        Shop result = cacheClient.queryWithLogicalExpire(
                "cache:shop:", 1L, Shop.class, fallback, 30L, TimeUnit.MINUTES);

        assertEquals("stale", result.getName());
        verify(valueOperations, timeout(1000))
                .set(eq("cache:shop:1"), argThat(logicalExpireValueWithName("fresh")));
    }

    @Test
    void shouldCacheAnEmptyValueWhenColdCacheFallbackFindsNothing() {
        when(valueOperations.get("cache:shop:404")).thenReturn(null);

        Shop result = cacheClient.queryWithLogicalExpire(
                "cache:shop:", 404L, Shop.class, ignored -> null, 30L, TimeUnit.MINUTES);

        assertNull(result);
        verify(valueOperations).set("cache:shop:404", "", RedisConstants.CACHE_NULL_TTL, TimeUnit.MINUTES);
    }

    private ArgumentMatcher<String> logicalExpireValueWithName(String expectedName) {
        return json -> {
            RedisData redisData = JSONUtil.toBean(json, RedisData.class);
            Shop shop = JSONUtil.toBean(JSONUtil.parseObj(redisData.getData()), Shop.class);
            return redisData.getExpireTime() != null && expectedName.equals(shop.getName());
        };
    }
}
