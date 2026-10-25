package io.github.frewily.campushub.identity.infrastructure;

import io.github.frewily.campushub.utils.RedisConstants;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class RedisLoginCodeStoreTest {
    private static final String PHONE = "13800138000";

    @SuppressWarnings("unchecked")
    @Test void acquireUsesOnlyCooldownSetIfAbsentAndPassesTrueFalseAndNullThrough() {
        StringRedisTemplate redis = mock(StringRedisTemplate.class);
        ValueOperations<String, String> values = mock(ValueOperations.class);
        when(redis.opsForValue()).thenReturn(values);
        RedisLoginCodeStore store = new RedisLoginCodeStore(redis);

        when(values.setIfAbsent(RedisConstants.LOGIN_CODE_COOLDOWN_KEY + PHONE, "1", 60L, TimeUnit.SECONDS)).thenReturn(true, false, null);

        assertEquals(Boolean.TRUE, store.acquireCooldown(PHONE));
        assertEquals(Boolean.FALSE, store.acquireCooldown(PHONE));
        assertNull(store.acquireCooldown(PHONE));
        verify(redis, times(3)).opsForValue();
        verify(values, times(3)).setIfAbsent(RedisConstants.LOGIN_CODE_COOLDOWN_KEY + PHONE, "1", 60L, TimeUnit.SECONDS);
        verify(values, never()).set(anyString(), anyString(), anyLong(), any());
        verify(redis, never()).delete(anyString());
        verifyNoMoreInteractions(values, redis);
    }

    @SuppressWarnings("unchecked")
    @Test void saveUsesOnlyCodeKeyAndTwoMinuteTtlAndPropagatesFailure() {
        StringRedisTemplate redis = mock(StringRedisTemplate.class);
        ValueOperations<String, String> values = mock(ValueOperations.class);
        when(redis.opsForValue()).thenReturn(values);
        RedisLoginCodeStore store = new RedisLoginCodeStore(redis);
        RuntimeException failure = new RuntimeException("redis write failed");
        doThrow(failure).when(values).set(RedisConstants.LOGIN_CODE_KEY + PHONE, "246810", 2L, TimeUnit.MINUTES);

        assertSame(failure, assertThrows(RuntimeException.class, () -> store.saveCode(PHONE, "246810")));
        verify(redis).opsForValue();
        verify(values).set(RedisConstants.LOGIN_CODE_KEY + PHONE, "246810", 2L, TimeUnit.MINUTES);
        verify(values, never()).setIfAbsent(anyString(), anyString(), anyLong(), any());
        verify(redis, never()).delete(anyString());
        verifyNoMoreInteractions(values, redis);
    }

    @SuppressWarnings("unchecked")
    @Test void cooldownSetFailurePropagatesWithoutCallingOtherOperations() {
        StringRedisTemplate redis = mock(StringRedisTemplate.class);
        ValueOperations<String, String> values = mock(ValueOperations.class);
        when(redis.opsForValue()).thenReturn(values);
        RuntimeException failure = new RuntimeException("redis cooldown failure");
        when(values.setIfAbsent(RedisConstants.LOGIN_CODE_COOLDOWN_KEY + PHONE, "1", 60L, TimeUnit.SECONDS))
                .thenThrow(failure);

        assertSame(failure, assertThrows(RuntimeException.class,
                () -> new RedisLoginCodeStore(redis).acquireCooldown(PHONE)));
        verify(redis).opsForValue();
        verify(values).setIfAbsent(RedisConstants.LOGIN_CODE_COOLDOWN_KEY + PHONE, "1", 60L, TimeUnit.SECONDS);
        verify(values, never()).set(anyString(), anyString(), anyLong(), any());
        verify(redis, never()).delete(anyString());
        verifyNoMoreInteractions(values, redis);
    }

    @Test void releaseDeletesOnlyCooldownKeyAndPropagatesFailure() {
        StringRedisTemplate redis = mock(StringRedisTemplate.class);
        RedisLoginCodeStore store = new RedisLoginCodeStore(redis);
        RuntimeException failure = new RuntimeException("redis delete failed");
        when(redis.delete(RedisConstants.LOGIN_CODE_COOLDOWN_KEY + PHONE)).thenThrow(failure);

        assertSame(failure, assertThrows(RuntimeException.class, () -> store.releaseCooldown(PHONE)));
        verify(redis).delete(RedisConstants.LOGIN_CODE_COOLDOWN_KEY + PHONE);
        verify(redis, never()).opsForValue();
        verifyNoMoreInteractions(redis);
    }
}
