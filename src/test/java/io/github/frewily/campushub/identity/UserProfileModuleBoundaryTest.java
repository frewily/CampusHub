package io.github.frewily.campushub.identity;

import io.github.frewily.campushub.controller.UserController;
import io.github.frewily.campushub.dto.UserDTO;
import io.github.frewily.campushub.dto.response.UserInfoResponse;
import io.github.frewily.campushub.identity.api.UserProfileController;
import io.github.frewily.campushub.identity.api.LogoutController;
import io.github.frewily.campushub.identity.api.LoginCodeController;
import io.github.frewily.campushub.identity.application.UserProfileQueryService;
import io.github.frewily.campushub.identity.application.UserProfileReader;
import org.junit.jupiter.api.Test;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.validation.annotation.Validated;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class UserProfileModuleBoundaryTest {
    @Test
    void profileRoutesMoveOutWhileLegacyUserWritesRemain() throws Exception {
        assertEquals("/user", UserProfileController.class.getAnnotation(RequestMapping.class).value()[0]);
        assertTrue(UserProfileController.class.isAnnotationPresent(Validated.class));
        assertArrayEquals(new Class<?>[]{UserProfileQueryService.class},
                UserProfileController.class.getDeclaredConstructors()[0].getParameterTypes());
        assertEquals(3L, Arrays.stream(UserProfileController.class.getDeclaredMethods())
                .filter(method -> method.isAnnotationPresent(GetMapping.class)).count());
        assertEquals(1L, Arrays.stream(UserController.class.getDeclaredMethods())
                .filter(method -> method.isAnnotationPresent(PostMapping.class)).count());
        Set<String> legacyWrites = new HashSet<>();
        Arrays.stream(UserController.class.getDeclaredMethods())
                .filter(method -> method.isAnnotationPresent(PostMapping.class))
                .forEach(method -> legacyWrites.add(method.getName() + " "
                        + method.getAnnotation(PostMapping.class).value()[0]));
        assertEquals(new HashSet<>(Arrays.asList("login /login")), legacyWrites);
        assertEquals("/user", LoginCodeController.class.getAnnotation(RequestMapping.class).value()[0]);
        assertEquals(1L, Arrays.stream(LoginCodeController.class.getDeclaredMethods())
                .filter(method -> method.isAnnotationPresent(PostMapping.class)).count());
        assertArrayEquals(new String[]{"code"}, Arrays.stream(LoginCodeController.class.getDeclaredMethods())
                .filter(method -> method.isAnnotationPresent(PostMapping.class)).findFirst().get()
                .getAnnotation(PostMapping.class).value());
        assertEquals("/user", LogoutController.class.getAnnotation(RequestMapping.class).value()[0]);
        assertEquals(1L, Arrays.stream(LogoutController.class.getDeclaredMethods())
                .filter(method -> method.isAnnotationPresent(PostMapping.class)).count());
        assertArrayEquals(new String[]{"/logout"}, Arrays.stream(LogoutController.class.getDeclaredMethods())
                .filter(method -> method.isAnnotationPresent(PostMapping.class))
                .findFirst().get().getAnnotation(PostMapping.class).value());
        assertEquals(0L, Arrays.stream(UserController.class.getDeclaredMethods())
                .filter(method -> method.isAnnotationPresent(GetMapping.class)).count());
        assertFalse(Arrays.stream(UserController.class.getDeclaredFields())
                .anyMatch(field -> field.getType().getSimpleName().equals("IUserInfoService")));
        assertEquals(2, UserProfileReader.class.getDeclaredMethods().length);
        assertEquals(2, UserProfileQueryService.class.getDeclaredMethods().length);
        assertTrue(Arrays.stream(UserProfileReader.class.getDeclaredMethods())
                .allMatch(method -> Arrays.equals(method.getParameterTypes(), new Class<?>[]{Long.class})));
        assertEquals(3, UserDTO.class.getDeclaredFields().length);
        assertEquals(9, UserInfoResponse.class.getDeclaredFields().length);

        Path root = Paths.get("src/main/java/io/github/frewily/campushub/service");
        assertFalse(Files.exists(root.resolve("IUserInfoService.java")));
        assertFalse(Files.exists(root.resolve("impl/UserInfoServiceImpl.java")));
    }

    @Test
    void applicationAndPortExposeOnlyDtosAndAvoidInfrastructureDependencies() throws Exception {
        Path directory = Paths.get("src/main/java/io/github/frewily/campushub/identity/application");
        for (String file : new String[]{"UserProfileQueryService.java", "UserProfileReader.java"}) {
            String source = new String(Files.readAllBytes(directory.resolve(file)), StandardCharsets.UTF_8);
            for (String forbidden : new String[]{"org.springframework", "org.apache.ibatis", "com.baomidou",
                    "cn.hutool", ".mapper.", "io.github.frewily.campushub.entity.",
                    "io.github.frewily.campushub.service.", ".infrastructure."}) {
                assertFalse(source.contains(forbidden), file + " must not depend on " + forbidden);
            }
        }
        String port = new String(Files.readAllBytes(directory.resolve("UserProfileReader.java")),
                StandardCharsets.UTF_8);
        assertTrue(port.contains("UserDTO"));
        assertTrue(port.contains("UserInfoResponse"));
        assertFalse(port.contains("UserInfo;"));
        assertFalse(port.contains("User;"));
    }
}
