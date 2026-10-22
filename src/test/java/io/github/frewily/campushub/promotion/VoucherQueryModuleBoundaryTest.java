package io.github.frewily.campushub.promotion;

import com.baomidou.mybatisplus.extension.service.IService;
import io.github.frewily.campushub.controller.VoucherController;
import io.github.frewily.campushub.entity.Voucher;
import io.github.frewily.campushub.promotion.api.VoucherQueryController;
import io.github.frewily.campushub.promotion.application.VoucherQueryService;
import io.github.frewily.campushub.service.IVoucherService;
import io.github.frewily.campushub.service.impl.VoucherServiceImpl;
import org.junit.jupiter.api.Test;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.*;

class VoucherQueryModuleBoundaryTest {
    @Test
    void catalogReadLeavesTheLegacyWriteControllerAndServiceContractIntact() throws Exception {
        assertFalse(Arrays.stream(VoucherController.class.getDeclaredMethods())
                .anyMatch(method -> method.getName().equals("queryVoucherOfShop")));
        assertFalse(Arrays.stream(IVoucherService.class.getDeclaredMethods())
                .anyMatch(method -> method.getName().equals("queryVoucherOfShop")));
        assertFalse(Arrays.stream(VoucherServiceImpl.class.getDeclaredMethods())
                .anyMatch(method -> method.getName().equals("queryVoucherOfShop")));
        assertEquals(2L, Arrays.stream(VoucherController.class.getDeclaredMethods())
                .filter(method -> method.isAnnotationPresent(PostMapping.class)).count());
        assertNotNull(IVoucherService.class.getMethod("addVoucher", Voucher.class));
        assertNotNull(IVoucherService.class.getMethod("addSeckillVoucher", Voucher.class));
        assertTrue(IService.class.isAssignableFrom(IVoucherService.class));
        assertEquals(1L, Arrays.stream(VoucherQueryController.class.getDeclaredMethods())
                .filter(method -> method.isAnnotationPresent(GetMapping.class)).count());
        assertArrayEquals(new Class<?>[]{VoucherQueryService.class},
                VoucherQueryController.class.getDeclaredConstructors()[0].getParameterTypes());
    }

    @Test
    void applicationLayerUsesOnlyItsQueryPortAndVoucherEntity() throws Exception {
        Path directory = Paths.get("src/main/java/io/github/frewily/campushub/promotion/application");
        for (String file : new String[]{"VoucherQueryService.java", "VoucherQueryRepository.java"}) {
            String source = new String(Files.readAllBytes(directory.resolve(file)), StandardCharsets.UTF_8);
            for (String forbidden : new String[]{"org.springframework", "org.apache.ibatis", "com.baomidou",
                    "cn.hutool", ".mapper.", "io.github.frewily.campushub.service.",
                    ".infrastructure."}) {
                assertFalse(source.contains(forbidden), file + " must not depend on " + forbidden);
            }
        }
    }
}
