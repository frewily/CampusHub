package io.github.frewily.campushub.social.infrastructure;

import io.github.frewily.campushub.social.application.FollowProjectionWriter;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

@Component
public class RedisFollowProjectionWriter implements FollowProjectionWriter {
    private final StringRedisTemplate redis;

    public RedisFollowProjectionWriter(StringRedisTemplate redis) { this.redis = redis; }

    @Override
    public void add(Long actorId, Long targetId, long timestamp) {
        redis.opsForZSet().add("follows:" + actorId, targetId.toString(), timestamp);
    }

    @Override
    public void remove(Long actorId, Long targetId) {
        redis.opsForZSet().remove("follows:" + actorId, targetId.toString());
    }
}
