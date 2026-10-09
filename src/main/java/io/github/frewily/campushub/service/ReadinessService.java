package io.github.frewily.campushub.service;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.RedisCallback;
import org.springframework.stereotype.Service;

@Service
public class ReadinessService {
    private final JdbcTemplate jdbc;
    private final StringRedisTemplate redis;
    public ReadinessService(JdbcTemplate jdbc, StringRedisTemplate redis) { this.jdbc = jdbc; this.redis = redis; }
    public boolean ready() {
        try {
            if (!Integer.valueOf(1).equals(jdbc.queryForObject("SELECT 1", Integer.class))) return false;
            return "PONG".equals(redis.execute((RedisCallback<String>) connection -> connection.ping()));
        } catch (RuntimeException unavailable) { return false; } // Health reveals no component, address or credential details.
    }
}
