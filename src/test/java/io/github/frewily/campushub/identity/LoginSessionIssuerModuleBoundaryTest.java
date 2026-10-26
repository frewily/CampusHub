package io.github.frewily.campushub.identity;

import io.github.frewily.campushub.identity.application.LoginAccount;
import io.github.frewily.campushub.identity.application.LoginSessionIssuer;
import io.github.frewily.campushub.identity.infrastructure.RedisLoginSessionIssuer;
import org.junit.jupiter.api.Test;
import org.springframework.stereotype.Component;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.core.io.ClassPathResource;

import java.lang.reflect.Modifier;
import java.util.Arrays;
import java.nio.charset.StandardCharsets;

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
        assertTrue(adapter.contains("DefaultRedisScript<Long>"));
        assertTrue(adapter.contains("redis.execute("));
        assertTrue(adapter.contains("Collections.singletonList(tokenKey)"));
        assertTrue(adapter.contains("TimeUnit.MINUTES.toMillis(LOGIN_USER_TTL)"));
        assertTrue(adapter.contains("\"登录会话签发未完成\""));
        assertFalse(adapter.contains("@Transactional"));
        assertFalse(adapter.contains("UserHolder"));
        assertFalse(adapter.contains("putAll(tokenKey, userMap)"));
        assertFalse(adapter.contains("expire(tokenKey, LOGIN_USER_TTL, TimeUnit.MINUTES)"));

        ClassPathResource resource = new ClassPathResource("login-session-publish.lua");
        assertTrue(resource.exists(), "login session publication script must be on the classpath");
        String lua;
        try (java.io.InputStream input = resource.getInputStream()) {
            lua = new String(readAll(input), StandardCharsets.UTF_8);
        }
        assertTrue(lua.contains("redis.call('EXISTS'"));
        assertTrue(lua.contains("redis.call('HSET'"));
        assertTrue(lua.contains("redis.pcall('PEXPIRE'"));
        assertTrue(lua.contains("redis.pcall('DEL'"));
        assertTrue(lua.contains("redis.pcall('HSET'"));
        assertTrue(lua.contains("'nickName'"));
        assertTrue(lua.contains("'id'"));
        assertTrue(lua.contains("'icon'"));
        assertTrue(lua.contains("tonumber(ARGV[1])"));
        assertTrue(lua.contains("return 1"));
        assertTrue(lua.contains("return 0"));
        assertTrue(lua.contains("return -1"));
    }

    private String source(String path) {
        try { return new String(java.nio.file.Files.readAllBytes(java.nio.file.Paths.get(path)),
                java.nio.charset.StandardCharsets.UTF_8); }
        catch (Exception e) { throw new AssertionError("Cannot read " + path, e); }
    }

    private byte[] readAll(java.io.InputStream input) throws java.io.IOException {
        java.io.ByteArrayOutputStream output = new java.io.ByteArrayOutputStream();
        byte[] buffer = new byte[1024];
        int count;
        while ((count = input.read(buffer)) != -1) output.write(buffer, 0, count);
        return output.toByteArray();
    }
}
