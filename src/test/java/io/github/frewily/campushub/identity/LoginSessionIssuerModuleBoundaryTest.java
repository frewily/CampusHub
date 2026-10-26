package io.github.frewily.campushub.identity;

import io.github.frewily.campushub.identity.application.LoginAccount;
import io.github.frewily.campushub.identity.application.LoginSessionIssuer;
import io.github.frewily.campushub.identity.infrastructure.RedisLoginSessionIssuer;
import org.junit.jupiter.api.Test;
import org.springframework.stereotype.Component;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.lang.reflect.Modifier;
import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.*;

class LoginSessionIssuerModuleBoundaryTest {
    @Test
    void portIsNarrowAndAdapterOwnsRedisImplementation() throws Exception {
        assertEquals(String.class, LoginSessionIssuer.class.getMethod("issue", LoginAccount.class).getReturnType());
        assertEquals(1, LoginSessionIssuer.class.getDeclaredMethods().length);
        assertTrue(LoginSessionIssuer.class.isAssignableFrom(RedisLoginSessionIssuer.class));
        assertTrue(RedisLoginSessionIssuer.class.isAnnotationPresent(Component.class));
        assertArrayEquals(new Class<?>[]{StringRedisTemplate.class}, Arrays.stream(RedisLoginSessionIssuer.class.getDeclaredConstructors())
                .filter(c -> Modifier.isPublic(c.getModifiers())).findFirst()
                .orElseThrow(() -> new AssertionError("public issuer constructor missing")).getParameterTypes());
        String port = source("src/main/java/io/github/frewily/campushub/identity/application/LoginSessionIssuer.java");
        for (String forbidden : new String[]{"org.springframework", "cn.hutool", "org.apache.ibatis", ".entity.", ".mapper.", ".infrastructure.", ".utils."}) {
            assertFalse(port.contains(forbidden), "issuer port must not depend on " + forbidden);
        }
        String service = source("src/main/java/io/github/frewily/campushub/service/impl/UserServiceImpl.java");
        assertTrue(service.contains("LoginSessionIssuer sessions"));
        assertTrue(service.contains("String token = sessions.issue(user)"));
        assertFalse(service.contains("BeanUtil"));
        assertFalse(service.contains("UserHolder"));
        String adapter = source("src/main/java/io/github/frewily/campushub/identity/infrastructure/RedisLoginSessionIssuer.java");
        assertTrue(adapter.contains("UUID.randomUUID().toString(true)"));
        assertTrue(adapter.contains("putAll(tokenKey, userMap)"));
        assertTrue(adapter.contains("expire(tokenKey, LOGIN_USER_TTL, TimeUnit.MINUTES)"));
        assertFalse(adapter.contains("@Transactional"));
        assertFalse(adapter.contains("UserHolder"));
    }

    private String source(String path) {
        try { return new String(java.nio.file.Files.readAllBytes(java.nio.file.Paths.get(path)),
                java.nio.charset.StandardCharsets.UTF_8); }
        catch (Exception e) { throw new AssertionError("Cannot read " + path, e); }
    }
}
