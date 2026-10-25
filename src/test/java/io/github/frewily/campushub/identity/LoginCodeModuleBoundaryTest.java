package io.github.frewily.campushub.identity;

import io.github.frewily.campushub.identity.application.LoginCodeGenerator;
import io.github.frewily.campushub.identity.application.LoginCodeService;
import io.github.frewily.campushub.identity.application.LoginCodeStore;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;

import static org.junit.jupiter.api.Assertions.*;

class LoginCodeModuleBoundaryTest {
    @Test void applicationDependsOnlyOnItsPortsAndPureJavaPhonePattern() throws Exception {
        assertArrayEquals(new Class<?>[]{LoginCodeStore.class, LoginCodeGenerator.class},
                LoginCodeService.class.getDeclaredConstructors()[0].getParameterTypes());
        for (String file : new String[]{"LoginCodeService.java", "LoginCodeStore.java", "LoginCodeGenerator.java"}) {
            String source = new String(Files.readAllBytes(Paths.get(
                    "src/main/java/io/github/frewily/campushub/identity/application", file)), StandardCharsets.UTF_8);
            for (String forbidden : new String[]{"cn.hutool", "Redis", "org.springframework", "RegexUtils",
                    "ThreadLocal", ".infrastructure.", ".mapper.", ".entity.", "com.baomidou"}) {
                assertFalse(source.contains(forbidden), file + " must not depend on " + forbidden);
            }
        }
        String service = new String(Files.readAllBytes(Paths.get(
                "src/main/java/io/github/frewily/campushub/identity/application/LoginCodeService.java")),
                StandardCharsets.UTF_8);
        assertTrue(service.contains("RegexPatterns.PHONE_REGEX") || service.contains("PHONE_REGEX"));
    }
}
