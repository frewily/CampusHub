package io.github.frewily.campushub.engagement;

import io.github.frewily.campushub.controller.UserController;
import io.github.frewily.campushub.engagement.api.CheckInController;
import io.github.frewily.campushub.engagement.application.CheckInService;
import io.github.frewily.campushub.engagement.application.CheckInStore;
import io.github.frewily.campushub.service.IUserService;
import io.github.frewily.campushub.service.impl.UserServiceImpl;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Clock;

import static org.junit.jupiter.api.Assertions.*;

class CheckInModuleBoundaryTest {
    @Test
    void accountAndLoginTypesNoLongerOwnCheckInMethods() {
        for (Class<?> type : new Class<?>[]{UserController.class, IUserService.class, UserServiceImpl.class}) {
            assertThrows(NoSuchMethodException.class, () -> type.getDeclaredMethod("sign"));
            assertThrows(NoSuchMethodException.class, () -> type.getDeclaredMethod("signCount"));
        }
        assertEquals(CheckInService.class, CheckInController.class.getDeclaredConstructors()[0].getParameterTypes()[0]);
        assertDoesNotThrow(() -> CheckInService.class.getConstructor(CheckInStore.class, Clock.class));
    }

    @Test
    void applicationSliceDoesNotImportWebPersistenceOrLegacyServices() throws Exception {
        Path directory = Paths.get("src/main/java/io/github/frewily/campushub/engagement/application");
        for (String file : new String[]{"CheckInService.java", "CheckInStore.java"}) {
            String source = new String(Files.readAllBytes(directory.resolve(file)), StandardCharsets.UTF_8);
            for (String forbidden : new String[]{"org.springframework", "com.baomidou", ".dto.", ".utils.",
                    ".mapper.", ".service.", ".infrastructure."}) {
                assertFalse(source.contains(forbidden), file + " must remain independent of " + forbidden);
            }
        }
    }
}
