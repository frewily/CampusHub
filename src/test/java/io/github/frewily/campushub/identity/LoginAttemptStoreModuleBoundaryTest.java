package io.github.frewily.campushub.identity;

import io.github.frewily.campushub.identity.application.LoginAttemptStore;
import io.github.frewily.campushub.identity.infrastructure.RedisLoginAttemptStore;
import io.github.frewily.campushub.service.impl.UserServiceImpl;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.*;

class LoginAttemptStoreModuleBoundaryTest {
    @Test void portIsLimitedToLoginVerificationStorage() throws Exception {
        Set<String> methods = Arrays.stream(LoginAttemptStore.class.getDeclaredMethods())
                .map(method -> method.getName()).collect(Collectors.toSet());
        assertEquals(new HashSet<>(Arrays.asList("findFailureCount", "consumeCode", "incrementFailureCount", "clearFailureCount")), methods);
        assertEquals(4, LoginAttemptStore.class.getDeclaredMethods().length);
        assertEquals(String.class, LoginAttemptStore.class.getMethod("findFailureCount", String.class).getReturnType());
        assertEquals(Long.class, LoginAttemptStore.class.getMethod("consumeCode", String.class, String.class).getReturnType());
        assertEquals(Long.class, LoginAttemptStore.class.getMethod("incrementFailureCount", String.class).getReturnType());
        assertEquals(void.class, LoginAttemptStore.class.getMethod("clearFailureCount", String.class).getReturnType());
        String port = source("identity/application/LoginAttemptStore.java");
        for (String forbidden : new String[]{"org.springframework", ".utils.", ".entity.", ".mapper.", ".infrastructure."}) {
            assertFalse(port.contains(forbidden), forbidden);
        }
        assertTrue(LoginAttemptStore.class.isAssignableFrom(RedisLoginAttemptStore.class));
        assertTrue(RedisLoginAttemptStore.class.isAnnotationPresent(Component.class));
        assertNotNull(RedisLoginAttemptStore.class.getConstructor(StringRedisTemplate.class));
        assertFalse(RedisLoginAttemptStore.class.isAnnotationPresent(Transactional.class));
    }

    @Test void serviceRetainsTransactionAndPolicyButNotRedisMechanics() throws Exception {
        String service = source("service/impl/UserServiceImpl.java");
        assertTrue(service.contains("LoginAttemptStore attempts"));
        assertTrue(service.contains("Long.parseLong(failureCount) >= LOGIN_FAILURE_LIMIT"));
        assertTrue(service.contains("attempts.consumeCode(phone, cacheCode)"));
        assertTrue(service.contains("attempts.incrementFailureCount(phone)"));
        assertTrue(service.contains("attempts.clearFailureCount(phone)"));
        for (String forbidden : new String[]{"StringRedisTemplate", "DefaultRedisScript", "ClassPathResource",
                "LOGIN_CODE_KEY", "LOGIN_FAILURE_KEY", "TimeUnit", "login-code-consume.lua", "login-failure-increment.lua"}) {
            assertFalse(service.contains(forbidden), forbidden);
        }
        assertTrue(UserServiceImpl.class.getMethod("login", io.github.frewily.campushub.dto.LoginFormDTO.class)
                .isAnnotationPresent(Transactional.class));
    }

    private String source(String path) throws Exception {
        return new String(Files.readAllBytes(Paths.get("src/main/java/io/github/frewily/campushub/" + path)), StandardCharsets.UTF_8);
    }
}
