package io.github.frewily.campushub.identity.infrastructure;

import io.github.frewily.campushub.identity.application.SessionTerminator;
import io.github.frewily.campushub.utils.UserHolder;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import static io.github.frewily.campushub.utils.RedisConstants.LOGIN_USER_KEY;

@Component
public class RedisSessionTerminator implements SessionTerminator {
    private final StringRedisTemplate redis;

    public RedisSessionTerminator(StringRedisTemplate redis) { this.redis = redis; }

    @Override
    public void terminate(String token) {
        // Retain the original order and false/null result semantics; do not swallow Redis failures.
        redis.delete(LOGIN_USER_KEY + token);
        UserHolder.removeUser();
    }
}
