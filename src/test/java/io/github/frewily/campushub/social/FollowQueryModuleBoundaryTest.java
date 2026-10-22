package io.github.frewily.campushub.social;

import com.baomidou.mybatisplus.extension.service.IService;
import io.github.frewily.campushub.mapper.FollowMapper;
import io.github.frewily.campushub.social.api.FollowController;
import io.github.frewily.campushub.social.api.FollowQueryController;
import io.github.frewily.campushub.social.application.FollowCommandService;
import io.github.frewily.campushub.social.application.FollowerReader;
import io.github.frewily.campushub.social.application.FollowQueryService;
import io.github.frewily.campushub.social.infrastructure.MyBatisFollowerReader;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.*;

import static org.junit.jupiter.api.Assertions.*;

class FollowQueryModuleBoundaryTest {
    @Test
    void oldFollowTypesAreRemovedAndSocialControllersKeepTheirBoundaries() throws Exception {
        assertThrows(ClassNotFoundException.class,
                () -> Class.forName("io.github.frewily.campushub.service.IFollowService"));
        assertThrows(ClassNotFoundException.class,
                () -> Class.forName("io.github.frewily.campushub.service.impl.FollowServiceImpl"));
        for (Class<?> type : new Class<?>[]{FollowController.class}) {
            for (String name : new String[]{"isFollow", "common"}) {
                assertThrows(NoSuchMethodException.class, () -> type.getDeclaredMethod(name, Long.class));
            }
        }
        assertFalse(IService.class.isAssignableFrom(FollowQueryService.class));
        assertArrayEquals(new Class<?>[]{FollowQueryService.class}, FollowQueryController.class.getDeclaredConstructors()[0].getParameterTypes());
        assertArrayEquals(new Class<?>[]{FollowCommandService.class},
                FollowController.class.getDeclaredConstructors()[0].getParameterTypes());
        assertEquals(java.util.List.class, FollowerReader.class.getMethod("findFollowerIds", Long.class).getReturnType());
        assertArrayEquals(new Class<?>[]{FollowMapper.class}, MyBatisFollowerReader.class.getDeclaredConstructors()[0].getParameterTypes());
    }

    @Test
    void applicationUsesPortsRatherThanWebSessionRedisMybatisOrLegacyServices() throws Exception {
        Path root = Paths.get("src/main/java/io/github/frewily/campushub/social/application");
        for (String file : new String[]{"FollowQueryService.java", "FollowLookupRepository.java", "FollowProjectionReader.java", "FollowUserReader.java", "FollowCommandService.java", "FollowCommandRepository.java", "FollowProjectionWriter.java", "FollowerReader.java"}) {
            String source = new String(Files.readAllBytes(root.resolve(file)), StandardCharsets.UTF_8);
            for (String forbidden : new String[]{"org.springframework", "com.baomidou", "cn.hutool", ".mapper.", ".utils.", ".service.", ".infrastructure."}) {
                assertFalse(source.contains(forbidden), file + " must not import " + forbidden);
            }
        }
    }
}
