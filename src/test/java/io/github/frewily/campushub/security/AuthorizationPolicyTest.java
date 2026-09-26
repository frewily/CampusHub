package io.github.frewily.campushub.security;

import io.github.frewily.campushub.controller.ShopController;
import io.github.frewily.campushub.controller.UploadController;
import io.github.frewily.campushub.controller.UserController;
import io.github.frewily.campushub.controller.VoucherController;
import io.github.frewily.campushub.controller.VoucherOrderController;
import io.github.frewily.campushub.entity.Shop;
import io.github.frewily.campushub.entity.Voucher;
import io.github.frewily.campushub.exception.ErrorCode;
import org.junit.jupiter.api.Test;
import org.springframework.security.access.prepost.PreAuthorize;

import static org.junit.jupiter.api.Assertions.assertEquals;

class AuthorizationPolicyTest {

    @Test
    void shouldDeclareResourceAndActivityPoliciesOnWriteEndpoints() throws Exception {
        assertPolicy(ShopController.class, "saveShop", new Class<?>[]{Shop.class},
                "@resourceAuthorization.canCreateShop(#shop.merchantId)");
        assertPolicy(ShopController.class, "updateShop", new Class<?>[]{Shop.class},
                "@resourceAuthorization.canManageShop(#shop.id)");
        assertPolicy(VoucherController.class, "addVoucher", new Class<?>[]{Voucher.class},
                "@resourceAuthorization.canManagePromotion(#voucher.shopId)");
        assertPolicy(VoucherController.class, "addSeckillVoucher", new Class<?>[]{Voucher.class},
                "@resourceAuthorization.canManagePromotion(#voucher.shopId)");
        assertPolicy(VoucherOrderController.class, "seckillVoucher", new Class<?>[]{Long.class},
                "@resourceAuthorization.canParticipateAsUser()");
        assertPolicy(UserController.class, "sign", new Class<?>[0],
                "@resourceAuthorization.canParticipateAsUser()");
        assertPolicy(UploadController.class, "deleteBlogImg", new Class<?>[]{String.class},
                "hasRole('ADMIN')");
    }

    @Test
    void authorizationFailuresUseForbiddenStatus() {
        assertEquals(403, ErrorCode.AUTHORIZATION_FAILED.getHttpStatus().value());
    }

    private void assertPolicy(Class<?> controller,
                              String method,
                              Class<?>[] parameters,
                              String expectedExpression) throws Exception {
        PreAuthorize annotation = controller.getMethod(method, parameters).getAnnotation(PreAuthorize.class);
        assertEquals(expectedExpression, annotation.value());
    }
}
