package io.github.frewily.campushub.identity;

import io.github.frewily.campushub.identity.application.SessionLogoutService;
import io.github.frewily.campushub.identity.application.SessionTerminator;
import io.github.frewily.campushub.identity.api.LogoutController;
import io.github.frewily.campushub.identity.infrastructure.RedisSessionTerminator;
import io.github.frewily.campushub.identity.config.SessionLogoutConfiguration;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.Bean;
import org.springframework.web.bind.annotation.RequestHeader;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import org.springframework.data.redis.core.StringRedisTemplate;

import static org.junit.jupiter.api.Assertions.*;

class SessionLogoutModuleBoundaryTest {
    @Test void pureApplicationPortAndApiDependenciesStayInsideTheModule() throws Exception {
        assertEquals(void.class, SessionTerminator.class.getDeclaredMethod("terminate", String.class).getReturnType());
        assertArrayEquals(new Class<?>[]{SessionTerminator.class}, SessionLogoutService.class.getDeclaredConstructors()[0].getParameterTypes());
        assertArrayEquals(new Class<?>[]{SessionLogoutService.class}, LogoutController.class.getDeclaredConstructors()[0].getParameterTypes());
        for (String file : new String[]{"SessionTerminator.java", "SessionLogoutService.java"}) {
            String source = new String(Files.readAllBytes(Paths.get("src/main/java/io/github/frewily/campushub/identity/application", file)), StandardCharsets.UTF_8);
            for (String dependency : new String[]{"org.springframework", "org.apache.ibatis", "com.baomidou", "cn.hutool", ".mapper.", ".utils.", ".entity.", ".service.", ".infrastructure."})
                assertFalse(source.contains(dependency), file + " depends on " + dependency);
        }
        assertArrayEquals(new Class<?>[]{StringRedisTemplate.class}, RedisSessionTerminator.class.getDeclaredConstructors()[0].getParameterTypes());
        assertEquals(1L, java.util.Arrays.stream(SessionLogoutConfiguration.class.getDeclaredMethods())
                .filter(method -> method.isAnnotationPresent(Bean.class)).count());
        assertEquals("authorization", LogoutController.class.getDeclaredMethod("logout", String.class)
                .getParameters()[0].getAnnotation(RequestHeader.class).value());
        for (Class<?> type : new Class<?>[]{io.github.frewily.campushub.controller.UserController.class,
                io.github.frewily.campushub.service.IUserService.class,
                io.github.frewily.campushub.service.impl.UserServiceImpl.class}) {
            assertThrows(NoSuchMethodException.class, () -> type.getDeclaredMethod("logout", String.class));
        }
    }
}
