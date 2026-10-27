package io.github.frewily.campushub.identity.infrastructure;

import io.github.frewily.campushub.utils.RedisConstants;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.util.Collections;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

class RedisLoginAttemptStoreTest {
    private static final String PHONE = "13990007001";

    @SuppressWarnings("unchecked")
    @Test void findFailureCountReturnsOriginalStringAndNullWithoutParsing() {
        StringRedisTemplate redis = mock(StringRedisTemplate.class);
        ValueOperations<String, String> values = mock(ValueOperations.class);
        when(redis.opsForValue()).thenReturn(values);
        when(values.get(RedisConstants.LOGIN_FAILURE_KEY + PHONE)).thenReturn("0007", null);
        RedisLoginAttemptStore store = new RedisLoginAttemptStore(redis);

        assertEquals("0007", store.findFailureCount(PHONE));
        assertNull(store.findFailureCount(PHONE));

        verify(redis, times(2)).opsForValue();
        verify(values, times(2)).get(RedisConstants.LOGIN_FAILURE_KEY + PHONE);
        verifyNoMoreInteractions(values, redis);
    }

    @Test void consumeCodeUsesOnlyTheCodeKeyAndOriginalScriptAndReturnsEveryLongUnchanged() throws Exception {
        StringRedisTemplate redis = mock(StringRedisTemplate.class);
        when(redis.execute(any(RedisScript.class), eq(Collections.singletonList(RedisConstants.LOGIN_CODE_KEY + PHONE)), eq(" 01 ")))
                .thenReturn(1L, 0L, null, -23L);
        RedisLoginAttemptStore store = new RedisLoginAttemptStore(redis);

        assertEquals(Long.valueOf(1L), store.consumeCode(PHONE, " 01 "));
        assertEquals(Long.valueOf(0L), store.consumeCode(PHONE, " 01 "));
        assertNull(store.consumeCode(PHONE, " 01 "));
        assertEquals(Long.valueOf(-23L), store.consumeCode(PHONE, " 01 "));

        @SuppressWarnings("unchecked")
        org.mockito.ArgumentCaptor<RedisScript> script = org.mockito.ArgumentCaptor.forClass(RedisScript.class);
        verify(redis, times(4)).execute(script.capture(), eq(Collections.singletonList(RedisConstants.LOGIN_CODE_KEY + PHONE)), eq(" 01 "));
        for (RedisScript candidate : script.getAllValues()) {
            assertEquals(scriptText("login-code-consume.lua"), candidate.getScriptAsString());
            assertEquals(Long.class, candidate.getResultType());
        }
        verifyNoMoreInteractions(redis);
    }

    @Test void incrementFailureCountUsesOnlyFailureKeyOriginalScriptAndTenMinuteSecondsArgument() throws Exception {
        StringRedisTemplate redis = mock(StringRedisTemplate.class);
        when(redis.execute(any(RedisScript.class), eq(Collections.singletonList(RedisConstants.LOGIN_FAILURE_KEY + PHONE)), eq("600")))
                .thenReturn(0L, 1L, null, -9L);
        RedisLoginAttemptStore store = new RedisLoginAttemptStore(redis);

        assertEquals(Long.valueOf(0L), store.incrementFailureCount(PHONE));
        assertEquals(Long.valueOf(1L), store.incrementFailureCount(PHONE));
        assertNull(store.incrementFailureCount(PHONE));
        assertEquals(Long.valueOf(-9L), store.incrementFailureCount(PHONE));

        @SuppressWarnings("unchecked")
        org.mockito.ArgumentCaptor<RedisScript> script = org.mockito.ArgumentCaptor.forClass(RedisScript.class);
        verify(redis, times(4)).execute(script.capture(), eq(Collections.singletonList(RedisConstants.LOGIN_FAILURE_KEY + PHONE)), eq("600"));
        for (RedisScript candidate : script.getAllValues()) {
            assertEquals(scriptText("login-failure-increment.lua"), candidate.getScriptAsString());
            assertEquals(Long.class, candidate.getResultType());
        }
        verifyNoMoreInteractions(redis);
    }

    @Test void clearFailureCountIgnoresFalseAndNullDeleteResults() {
        StringRedisTemplate redis = mock(StringRedisTemplate.class);
        when(redis.delete(RedisConstants.LOGIN_FAILURE_KEY + PHONE)).thenReturn(false, null);
        RedisLoginAttemptStore store = new RedisLoginAttemptStore(redis);

        assertDoesNotThrow(() -> store.clearFailureCount(PHONE));
        assertDoesNotThrow(() -> store.clearFailureCount(PHONE));

        verify(redis, times(2)).delete(RedisConstants.LOGIN_FAILURE_KEY + PHONE);
        verifyNoMoreInteractions(redis);
    }

    @Test void redisFailuresPropagateAsTheSameException() {
        StringRedisTemplate redis = mock(StringRedisTemplate.class);
        ValueOperations<String, String> values = mock(ValueOperations.class);
        when(redis.opsForValue()).thenReturn(values);
        RuntimeException getFailure = new IllegalStateException("get failed");
        RuntimeException consumeFailure = new IllegalArgumentException("consume failed");
        RuntimeException incrementFailure = new UnsupportedOperationException("increment failed");
        RuntimeException deleteFailure = new IllegalStateException("delete failed");
        when(values.get(RedisConstants.LOGIN_FAILURE_KEY + PHONE)).thenThrow(getFailure);
        when(redis.execute(any(RedisScript.class), eq(Collections.singletonList(RedisConstants.LOGIN_CODE_KEY + PHONE)), eq("123456")))
                .thenThrow(consumeFailure);
        when(redis.execute(any(RedisScript.class), eq(Collections.singletonList(RedisConstants.LOGIN_FAILURE_KEY + PHONE)), eq("600")))
                .thenThrow(incrementFailure);
        when(redis.delete(RedisConstants.LOGIN_FAILURE_KEY + PHONE)).thenThrow(deleteFailure);
        RedisLoginAttemptStore store = new RedisLoginAttemptStore(redis);

        assertSame(getFailure, assertThrows(IllegalStateException.class, () -> store.findFailureCount(PHONE)));
        assertSame(consumeFailure, assertThrows(IllegalArgumentException.class, () -> store.consumeCode(PHONE, "123456")));
        assertSame(incrementFailure, assertThrows(UnsupportedOperationException.class, () -> store.incrementFailureCount(PHONE)));
        assertSame(deleteFailure, assertThrows(IllegalStateException.class, () -> store.clearFailureCount(PHONE)));
    }
    private String scriptText(String name) throws Exception {
        try (java.io.InputStream input = new org.springframework.core.io.ClassPathResource(name).getInputStream()) {
            return org.springframework.util.StreamUtils.copyToString(input, java.nio.charset.StandardCharsets.UTF_8);
        }
    }
}
