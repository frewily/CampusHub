package io.github.frewily.campushub.identity.infrastructure;

import io.github.frewily.campushub.identity.application.LoginAttemptStore;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Component;

import java.util.Collections;
import java.util.concurrent.TimeUnit;

import static io.github.frewily.campushub.utils.RedisConstants.LOGIN_CODE_KEY;
import static io.github.frewily.campushub.utils.RedisConstants.LOGIN_FAILURE_KEY;
import static io.github.frewily.campushub.utils.RedisConstants.LOGIN_FAILURE_TTL;

@Component
public class RedisLoginAttemptStore implements LoginAttemptStore {
    private static final DefaultRedisScript<Long> LOGIN_CODE_CONSUME_SCRIPT;
    private static final DefaultRedisScript<Long> LOGIN_FAILURE_INCREMENT_SCRIPT;

    static {
        LOGIN_CODE_CONSUME_SCRIPT = new DefaultRedisScript<>();
        LOGIN_CODE_CONSUME_SCRIPT.setLocation(new ClassPathResource("login-code-consume.lua"));
        LOGIN_CODE_CONSUME_SCRIPT.setResultType(Long.class);

        LOGIN_FAILURE_INCREMENT_SCRIPT = new DefaultRedisScript<>();
        LOGIN_FAILURE_INCREMENT_SCRIPT.setLocation(new ClassPathResource("login-failure-increment.lua"));
        LOGIN_FAILURE_INCREMENT_SCRIPT.setResultType(Long.class);
    }

    private final StringRedisTemplate redis;

    public RedisLoginAttemptStore(StringRedisTemplate redis) { this.redis = redis; }

    @Override
    public String findFailureCount(String phone) {
        return redis.opsForValue().get(LOGIN_FAILURE_KEY + phone);
    }

    @Override
    public Long consumeCode(String phone, String code) {
        return redis.execute(LOGIN_CODE_CONSUME_SCRIPT,
                Collections.singletonList(LOGIN_CODE_KEY + phone), code);
    }

    @Override
    public Long incrementFailureCount(String phone) {
        return redis.execute(LOGIN_FAILURE_INCREMENT_SCRIPT,
                Collections.singletonList(LOGIN_FAILURE_KEY + phone),
                String.valueOf(TimeUnit.MINUTES.toSeconds(LOGIN_FAILURE_TTL)));
    }

    @Override
    public void clearFailureCount(String phone) { redis.delete(LOGIN_FAILURE_KEY + phone); }
}
