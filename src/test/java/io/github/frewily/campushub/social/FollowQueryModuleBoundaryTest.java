package io.github.frewily.campushub.social;

import com.baomidou.mybatisplus.extension.service.IService;
import io.github.frewily.campushub.controller.FollowController;
import io.github.frewily.campushub.service.IFollowService;
import io.github.frewily.campushub.service.impl.FollowServiceImpl;
import io.github.frewily.campushub.social.api.FollowQueryController;
import io.github.frewily.campushub.social.application.FollowQueryService;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.*;

import static org.junit.jupiter.api.Assertions.*;

class FollowQueryModuleBoundaryTest {
    @Test
    void legacyWriteTypesNoLongerOwnTheTwoPublicQueryMethods() {
        for (Class<?> type : new Class<?>[]{FollowController.class, IFollowService.class, FollowServiceImpl.class}) {
            for (String name : new String[]{"isFollow", "common"}) {
                assertThrows(NoSuchMethodException.class, () -> type.getDeclaredMethod(name, Long.class));
            }
        }
        assertFalse(IService.class.isAssignableFrom(FollowQueryService.class));
        assertArrayEquals(new Class<?>[]{FollowQueryService.class}, FollowQueryController.class.getDeclaredConstructors()[0].getParameterTypes());
        assertDoesNotThrow(() -> IFollowService.class.getMethod("follow", Long.class, Boolean.class));
        assertTrue(IService.class.isAssignableFrom(IFollowService.class), "publication still uses the legacy follower query chain in this stage");
    }

    @Test
    void applicationUsesPortsRatherThanWebSessionRedisMybatisOrLegacyServices() throws Exception {
        Path root = Paths.get("src/main/java/io/github/frewily/campushub/social/application");
        for (String file : new String[]{"FollowQueryService.java", "FollowLookupRepository.java", "FollowProjectionReader.java", "FollowUserReader.java"}) {
            String source = new String(Files.readAllBytes(root.resolve(file)), StandardCharsets.UTF_8);
            for (String forbidden : new String[]{"org.springframework", "com.baomidou", "cn.hutool", ".mapper.", ".utils.", ".service.", ".infrastructure."}) {
                assertFalse(source.contains(forbidden), file + " must not import " + forbidden);
            }
        }
    }
}
