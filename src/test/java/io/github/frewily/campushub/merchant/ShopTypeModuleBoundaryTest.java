package io.github.frewily.campushub.merchant;

import com.baomidou.mybatisplus.extension.service.IService;
import io.github.frewily.campushub.merchant.api.ShopTypeController;
import io.github.frewily.campushub.merchant.application.ShopTypeQueryService;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

import static org.junit.jupiter.api.Assertions.*;

class ShopTypeModuleBoundaryTest {
    @Test
    void categoryEndpointNoLongerDependsOnALegacyGenericCrudService() {
        for (String old : new String[]{"controller.ShopTypeController", "service.IShopTypeService", "service.impl.ShopTypeServiceImpl"}) {
            assertThrows(ClassNotFoundException.class, () -> Class.forName("io.github.frewily.campushub." + old));
        }
        assertFalse(IService.class.isAssignableFrom(ShopTypeQueryService.class));
        assertArrayEquals(new Class<?>[]{ShopTypeQueryService.class}, ShopTypeController.class.getDeclaredConstructors()[0].getParameterTypes());
    }

    @Test
    void queryOrchestrationDependsOnPortsInsteadOfFrameworkOrCacheJson() throws Exception {
        Path directory = Paths.get("src/main/java/io/github/frewily/campushub/merchant/application");
        for (String file : new String[]{"ShopTypeQueryService.java", "ShopTypeRepository.java", "ShopTypeCache.java"}) {
            String source = new String(Files.readAllBytes(directory.resolve(file)), StandardCharsets.UTF_8);
            for (String forbidden : new String[]{"org.springframework", "com.baomidou", "cn.hutool", ".mapper.", ".utils.", ".infrastructure.", ".dto."}) {
                assertFalse(source.contains(forbidden), file + " must not depend on " + forbidden);
            }
        }
    }
}
