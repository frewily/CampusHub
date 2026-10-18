package io.github.frewily.campushub.merchant.infrastructure;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import io.github.frewily.campushub.entity.ShopType;
import io.github.frewily.campushub.mapper.ShopTypeMapper;
import io.github.frewily.campushub.merchant.application.ShopTypeCache;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.util.Collections;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class ShopTypeAdaptersTest {
    private StringRedisTemplate redis;
    private ValueOperations<String, String> values;
    private RedisShopTypeCache cache;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        redis = mock(StringRedisTemplate.class);
        values = mock(ValueOperations.class);
        when(redis.opsForValue()).thenReturn(values);
        cache = new RedisShopTypeCache(redis);
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {" ", "\t\n"})
    void nullEmptyAndBlankStringsKeepTheMissSemantics(String raw) {
        when(values.get("cache:shop:type")).thenReturn(raw);
        assertFalse(cache.read().isHit());
        verify(values).get("cache:shop:type");
        verifyNoMoreInteractions(values);
    }

    @Test
    void legacyJsonDecodesWithoutChangingOrderOrRefreshingTtl() {
        when(values.get("cache:shop:type")).thenReturn("[{\"id\":9007199254740993,\"name\":\"cached\",\"icon\":\"/icon.png\",\"sort\":9},"
                + "{\"id\":2,\"name\":\"other\",\"sort\":1}]");
        ShopTypeCache.Lookup result = cache.read();
        assertTrue(result.isHit());
        assertEquals(9007199254740993L, result.getItems().get(0).getId());
        assertEquals("/icon.png", result.getItems().get(0).getIcon());
        assertEquals(2L, result.getItems().get(1).getId());
        verify(values).get("cache:shop:type");
        verifyNoMoreInteractions(values);
        verify(redis).opsForValue();
        verifyNoMoreInteractions(redis);
    }

    @Test
    void emptyArrayRemainsAHit() {
        when(values.get("cache:shop:type")).thenReturn("[]");
        ShopTypeCache.Lookup result = cache.read();
        assertTrue(result.isHit());
        assertTrue(result.getItems().isEmpty());
    }

    @Test
    void malformedJsonIsNotReclassifiedAsAMiss() {
        when(values.get("cache:shop:type")).thenReturn("{broken");
        assertThrows(RuntimeException.class, cache::read);
        verify(values).get("cache:shop:type");
        verifyNoMoreInteractions(values);
    }

    @Test
    void emptyWriteKeepsTheExistingKeyArrayAndThirtyMinuteTtl() {
        cache.write(Collections.emptyList());
        verify(values).set("cache:shop:type", "[]", 30L, TimeUnit.MINUTES);
        verifyNoMoreInteractions(values);
    }

    @Test
    @SuppressWarnings({"rawtypes", "unchecked"})
    void repositoryUsesOnlyAscendingSortWithTheOriginalMapper() {
        ShopTypeMapper mapper = mock(ShopTypeMapper.class);
        List<ShopType> rows = Collections.singletonList(new ShopType().setId(1L));
        when(mapper.selectList(any())).thenReturn(rows);
        assertSame(rows, new MyBatisShopTypeRepository(mapper).findAllOrderBySort());
        ArgumentCaptor<QueryWrapper> query = ArgumentCaptor.forClass(QueryWrapper.class);
        verify(mapper).selectList(query.capture());
        assertEquals("ORDER BY sort ASC", query.getValue().getSqlSegment().trim());
        assertTrue(query.getValue().getParamNameValuePairs().isEmpty());
        verifyNoMoreInteractions(mapper);
    }
}
