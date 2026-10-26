package io.github.frewily.campushub.identity.infrastructure;

import cn.hutool.core.bean.BeanUtil;
import cn.hutool.core.bean.copier.CopyOptions;
import cn.hutool.core.lang.UUID;
import io.github.frewily.campushub.dto.UserDTO;
import io.github.frewily.campushub.identity.application.LoginAccount;
import io.github.frewily.campushub.identity.application.LoginSessionIssuer;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.Collections;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import static io.github.frewily.campushub.utils.RedisConstants.LOGIN_USER_KEY;
import static io.github.frewily.campushub.utils.RedisConstants.LOGIN_USER_TTL;

@Component
public class RedisLoginSessionIssuer implements LoginSessionIssuer {
    private static final DefaultRedisScript<Long> SESSION_PUBLISH_SCRIPT;

    static {
        SESSION_PUBLISH_SCRIPT = new DefaultRedisScript<>();
        SESSION_PUBLISH_SCRIPT.setLocation(new ClassPathResource("login-session-publish.lua"));
        SESSION_PUBLISH_SCRIPT.setResultType(Long.class);
    }

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
        Long published = redis.execute(SESSION_PUBLISH_SCRIPT, Collections.singletonList(tokenKey),
                String.valueOf(TimeUnit.MINUTES.toMillis(LOGIN_USER_TTL)),
                userMap.get("id"), userMap.get("nickName"), userMap.get("icon"));
        if (!Long.valueOf(1L).equals(published)) {
            throw new IllegalStateException("登录会话签发未完成");
        }
        return token;
    }
}
