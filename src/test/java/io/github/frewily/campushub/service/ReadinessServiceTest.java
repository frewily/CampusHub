package io.github.frewily.campushub.service;

import org.junit.jupiter.api.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.data.redis.core.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class ReadinessServiceTest {
    @Test void requiresBothAuthenticatedDependenciesAndRejectsFailures() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class); StringRedisTemplate redis = mock(StringRedisTemplate.class);
        ReadinessService service = new ReadinessService(jdbc, redis);
        when(jdbc.queryForObject("SELECT 1", Integer.class)).thenReturn(1);
        when(redis.execute(any(RedisCallback.class))).thenReturn("PONG"); assertTrue(service.ready());
        when(redis.execute(any(RedisCallback.class))).thenReturn(null); assertFalse(service.ready());
        when(redis.execute(any(RedisCallback.class))).thenThrow(new IllegalStateException("synthetic private detail")); assertFalse(service.ready());
        when(jdbc.queryForObject("SELECT 1", Integer.class)).thenThrow(new IllegalStateException("synthetic private detail")); assertFalse(service.ready());
    }
}
