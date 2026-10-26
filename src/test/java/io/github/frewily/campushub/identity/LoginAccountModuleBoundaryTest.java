package io.github.frewily.campushub.identity;

import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.baomidou.mybatisplus.extension.service.IService;
import io.github.frewily.campushub.identity.application.LoginAccount;
import io.github.frewily.campushub.identity.application.LoginAccountRepository;
import io.github.frewily.campushub.service.IUserService;
import io.github.frewily.campushub.service.impl.UserServiceImpl;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.annotation.Transactional;

import java.lang.reflect.Modifier;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.*;

class LoginAccountModuleBoundaryTest {
    @Test
    void loginAccountIsImmutableAndPortContainsOnlyAccountOperations() {
        assertTrue(Modifier.isFinal(LoginAccount.class.getModifiers()));
        assertArrayEquals(new Class<?>[]{Long.class, String.class, String.class, String.class},
                Arrays.stream(LoginAccount.class.getDeclaredConstructors()).findFirst().get().getParameterTypes());
        Set<String> getters = Arrays.stream(LoginAccount.class.getDeclaredMethods())
                .map(method -> method.getName()).filter(name -> name.startsWith("get"))
                .collect(Collectors.toSet());
        assertEquals(new HashSet<>(Arrays.asList("getId", "getNickName", "getIcon", "getStatus")), getters);
        assertTrue(Arrays.stream(LoginAccount.class.getDeclaredFields()).allMatch(field -> Modifier.isPrivate(field.getModifiers())
                && Modifier.isFinal(field.getModifiers())));
        for (java.lang.reflect.Method method : LoginAccountRepository.class.getDeclaredMethods()) {
            assertEquals(LoginAccount.class, method.getReturnType());
            assertArrayEquals(new Class<?>[]{String.class}, method.getParameterTypes());
        }
        assertEquals(2, LoginAccountRepository.class.getDeclaredMethods().length);
        assertTrue(Arrays.stream(LoginAccountRepository.class.getDeclaredMethods()).anyMatch(method -> method.getName().equals("findByPhone")));
        assertTrue(Arrays.stream(LoginAccountRepository.class.getDeclaredMethods()).anyMatch(method -> method.getName().equals("createWithPhone")));
        for (String source : new String[]{LoginAccount.class.getName().replace('.', '/') + ".java",
                LoginAccountRepository.class.getName().replace('.', '/') + ".java"}) {
            String text = read("src/main/java/" + source);
            for (String forbidden : new String[]{"org.springframework", "org.apache.ibatis", "com.baomidou",
                    "UserMapper", "User;", "Redis"}) assertFalse(text.contains(forbidden), source + " contains " + forbidden);
        }
    }

    @Test
    void legacyLoginServiceNoLongerExposesCrudOrPersistenceDependencies() throws Exception {
        assertFalse(IService.class.isAssignableFrom(IUserService.class));
        assertFalse(ServiceImpl.class.isAssignableFrom(UserServiceImpl.class));
        assertEquals(1, IUserService.class.getDeclaredMethods().length);
        assertEquals("login", IUserService.class.getDeclaredMethods()[0].getName());
        assertTrue(UserServiceImpl.class.getMethod("login", io.github.frewily.campushub.dto.LoginFormDTO.class)
                .isAnnotationPresent(Transactional.class));
        Set<Class<?>> injected = Arrays.stream(UserServiceImpl.class.getDeclaredFields())
                .map(field -> field.getType()).collect(Collectors.toSet());
        assertTrue(injected.contains(LoginAccountRepository.class));
        assertFalse(injected.contains(io.github.frewily.campushub.mapper.UserMapper.class));
        assertFalse(injected.contains(io.github.frewily.campushub.entity.User.class));
        String source = read("src/main/java/io/github/frewily/campushub/service/impl/UserServiceImpl.java");
        assertTrue(source.contains("accounts.findByPhone(phone)"));
        assertTrue(source.contains("accounts.createWithPhone(phone)"));
        assertTrue(source.contains("sessions.issue(user)"));
        assertTrue(source.contains("stringRedisTemplate"));
        assertFalse(source.contains("UserMapper"));
        assertFalse(source.contains("createUserWithPhone"));
    }

    @Test
    void sessionMappingBelongsToIssuerAdapter() {
        String source = read("src/main/java/io/github/frewily/campushub/identity/infrastructure/RedisLoginSessionIssuer.java");
        assertTrue(source.contains("BeanUtil.copyProperties(account, UserDTO.class)"));
        assertTrue(source.contains("BeanUtil.beanToMap(userDTO"));
        assertTrue(source.contains("setIgnoreNullValue(true)"));
        assertTrue(source.contains("fieldValue.toString()"));
    }

    private String read(String path) {
        try { return new String(Files.readAllBytes(Paths.get(path)), StandardCharsets.UTF_8); }
        catch (Exception exception) { throw new AssertionError("Cannot read " + path, exception); }
    }
}
