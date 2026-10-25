package io.github.frewily.campushub.identity.infrastructure;

import io.github.frewily.campushub.identity.application.LoginCodeStore;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.util.concurrent.TimeUnit;

import static io.github.frewily.campushub.utils.RedisConstants.*;

@Component
public class RedisLoginCodeStore implements LoginCodeStore {
    private final StringRedisTemplate redis;

    public RedisLoginCodeStore(StringRedisTemplate redis) { this.redis = redis; }

    @Override
    public Boolean acquireCooldown(String phone) {
        return redis.opsForValue().setIfAbsent(LOGIN_CODE_COOLDOWN_KEY + phone, "1",
                LOGIN_CODE_COOLDOWN_TTL, TimeUnit.SECONDS);
    }

    @Override
    public void saveCode(String phone, String code) {
        redis.opsForValue().set(LOGIN_CODE_KEY + phone, code, LOGIN_CODE_TTL, TimeUnit.MINUTES);
    }

    @Override
    public void releaseCooldown(String phone) { redis.delete(LOGIN_CODE_COOLDOWN_KEY + phone); }
}
