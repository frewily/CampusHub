package io.github.frewily.campushub.social.infrastructure;

import io.github.frewily.campushub.social.application.FollowProjectionReader;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.util.Set;

@Component
public class RedisFollowProjectionReader implements FollowProjectionReader {
    private final StringRedisTemplate redis;

    public RedisFollowProjectionReader(StringRedisTemplate redis) { this.redis = redis; }

    @Override
    public Set<String> following(Long userId) {
        return redis.opsForZSet().range("follows:" + userId, 0, -1);
    }
}
