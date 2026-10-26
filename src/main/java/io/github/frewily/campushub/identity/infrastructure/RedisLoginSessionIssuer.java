package io.github.frewily.campushub.identity.infrastructure;

import cn.hutool.core.bean.BeanUtil;
import cn.hutool.core.bean.copier.CopyOptions;
import cn.hutool.core.lang.UUID;
import io.github.frewily.campushub.dto.UserDTO;
import io.github.frewily.campushub.identity.application.LoginAccount;
import io.github.frewily.campushub.identity.application.LoginSessionIssuer;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import static io.github.frewily.campushub.utils.RedisConstants.LOGIN_USER_KEY;
import static io.github.frewily.campushub.utils.RedisConstants.LOGIN_USER_TTL;

@Component
public class RedisLoginSessionIssuer implements LoginSessionIssuer {
    private final StringRedisTemplate redis;

    public RedisLoginSessionIssuer(StringRedisTemplate redis) { this.redis = redis; }

    @Override
    public String issue(LoginAccount account) {
        String token = UUID.randomUUID().toString(true);
        UserDTO userDTO = BeanUtil.copyProperties(account, UserDTO.class);
        Map<String, Object> userMap = BeanUtil.beanToMap(userDTO, new HashMap<>(),
                CopyOptions.create()
                        .setIgnoreNullValue(true)
                        .setFieldValueEditor((fieldName, fieldValue) -> fieldValue != null ? fieldValue.toString() : "")
        );
        String tokenKey = LOGIN_USER_KEY + token;
        redis.opsForHash().putAll(tokenKey, userMap);
        // Preserve the existing separate operations and ignored Boolean result; no new atomicity claim.
        redis.expire(tokenKey, LOGIN_USER_TTL, TimeUnit.MINUTES);
        return token;
    }
}
