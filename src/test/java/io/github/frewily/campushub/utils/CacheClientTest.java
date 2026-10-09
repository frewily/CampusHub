package io.github.frewily.campushub.utils;

import cn.hutool.json.JSONObject;
import io.github.frewily.campushub.entity.Shop;
import io.github.frewily.campushub.exception.*;
import org.junit.jupiter.api.*;
import org.springframework.data.redis.core.*;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class CacheClientTest {
    private StringRedisTemplate redis;
    private ValueOperations<String, String> values;
    private CacheClient cache;
    private AtomicReference<String> payload;
    private long ttl;

    @BeforeEach void setup() {
        redis = mock(StringRedisTemplate.class); values = mock(ValueOperations.class);
        cache = new CacheClient(redis); payload = new AtomicReference<>("");
        when(redis.opsForValue()).thenReturn(values);
        when(values.setIfAbsent(anyString(), anyString(), eq(10L), eq(TimeUnit.SECONDS))).thenReturn(true);
        when(redis.execute(any(RedisScript.class), anyList())).thenAnswer(inv -> Arrays.asList("", payload.get()));
        when(redis.execute(any(RedisScript.class), anyList(), anyString(), anyString(), anyString(), anyString()))
                .thenAnswer(inv -> { payload.set(inv.getArgument(4)); ttl = Long.parseLong(inv.getArgument(5)); return 1L; });
        when(redis.execute(any(RedisScript.class), anyList(), anyString())).thenReturn(1L);
    }

    @AfterEach void clear() { TransactionSynchronizationManager.clear(); Thread.interrupted(); }

    @Test void coldThenWarmUsesOneDatabaseLoadAndBoundedPhysicalTtl() {
        java.util.concurrent.atomic.AtomicInteger loads = new java.util.concurrent.atomic.AtomicInteger();
        for (int i=0; i<2; i++) assertEquals("fresh", cache.queryShop(1L, Shop.class,
                id -> { loads.incrementAndGet(); return new Shop().setId(id).setName("fresh"); }).getName());
        assertEquals(1, loads.get()); assertTrue(ttl>=60000 && ttl<=75000);
        assertArrayEquals(new long[]{1,0,1,1,0,0}, cache.statistics());
    }
    @Test void missingRowsAreNegativeCachedWithShortTtl() {
        assertNull(cache.queryShop(404L, Shop.class, id -> null));
        assertNull(cache.queryShop(404L, Shop.class, id -> { fail("negative hit must not read DB"); return null; }));
        assertTrue(ttl>=10000 && ttl<=15000);
        assertArrayEquals(new long[]{0,1,1,1,0,0}, cache.statistics());
    }
    @Test void wrongEpochPayloadIsIgnoredAndRebuilt() {
        payload.set(new JSONObject().set("epoch", "old").set("empty", false)
                .set("data", new Shop().setId(1L).setName("old")).toString());
        assertEquals("fresh", cache.queryShop(1L, Shop.class, id -> new Shop().setName("fresh")).getName());
    }
    @Test void invalidEnvelopeFailsClosedInsteadOfAnInventedMissingRow() {
        payload.set("{\"epoch\":\"\",\"empty\":\"false\"}");
        assertUnavailable(() -> cache.queryShop(1L, Shop.class, id -> { fail("no DB fallback"); return null; }));
    }
    @Test void unknownRedisResultIs503NotSuccess() {
        when(redis.execute(any(RedisScript.class), anyList(), anyString(), anyString(), anyString(), anyString())).thenReturn(null);
        assertUnavailable(() -> cache.queryShop(1L, Shop.class, id -> new Shop().setId(id)));
    }
    @Test void contentionDoesNotFanOutToDatabaseAndIsBounded() {
        when(values.setIfAbsent(anyString(), anyString(), eq(10L), eq(TimeUnit.SECONDS))).thenReturn(false);
        long start = System.nanoTime();
        assertUnavailable(() -> cache.queryShop(1L, Shop.class, id -> { fail("no DB load without lock"); return null; }));
        assertTrue(TimeUnit.NANOSECONDS.toMillis(System.nanoTime()-start)<2000);
    }
    @Test void interruptPreservesSignalAndFailsWithoutDirectDatabaseLoad() {
        when(values.setIfAbsent(anyString(), anyString(), eq(10L), eq(TimeUnit.SECONDS))).thenReturn(false);
        Thread.currentThread().interrupt();
        assertUnavailable(() -> cache.queryShop(1L, Shop.class, id -> { fail("no DB load"); return null; }));
        assertTrue(Thread.currentThread().isInterrupted());
    }
    @Test void activeTransactionCannotPopulatePublicCacheFromUncommittedOrOldSnapshot() {
        TransactionSynchronizationManager.setActualTransactionActive(true);
        clearInvocations(redis, values);
        assertUnavailable(() -> cache.queryShop(1L, Shop.class, id -> { fail("no transaction snapshot load"); return null; }));
        verifyNoInteractions(redis, values);
    }
    @Test void invalidIdentifierDoesNotTouchRedis() {
        clearInvocations(redis, values);
        assertEquals(ErrorCode.VALIDATION_FAILED, assertThrows(BusinessException.class,
                () -> cache.queryShop(0L, Shop.class, id -> null)).getErrorCode());
        verifyNoInteractions(redis, values);
    }
    @Test void jitterIsAlwaysInsideTheDocumentedPositiveAndNegativeBounds() {
        Set<Long> seen = new HashSet<>();
        for(int i=0;i<100;i++) {
            long positive=CacheClient.positiveTtlMillis(), negative=CacheClient.negativeTtlMillis();
            assertTrue(positive>=60000 && positive<=75000); assertTrue(negative>=10000 && negative<=15000);
            seen.add(positive);
        }
        assertTrue(seen.size()>1);
    }
    private void assertUnavailable(Runnable action) {
        assertEquals(ErrorCode.SHOP_STATE_UNAVAILABLE, assertThrows(BusinessException.class, action::run).getErrorCode());
    }
}
