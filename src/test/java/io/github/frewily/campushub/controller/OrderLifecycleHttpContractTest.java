package io.github.frewily.campushub.controller;

import io.github.frewily.campushub.config.*;
import io.github.frewily.campushub.dto.response.OrderStatusResponse;
import io.github.frewily.campushub.exception.*;
import io.github.frewily.campushub.mapper.AccountAccessMapper;
import io.github.frewily.campushub.security.*;
import io.github.frewily.campushub.service.*;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.data.redis.core.*;
import org.springframework.test.web.servlet.MockMvc;
import java.util.*;
import static io.github.frewily.campushub.utils.RedisConstants.LOGIN_USER_KEY;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(classes = OrderLifecycleHttpContractTest.Application.class)
@AutoConfigureMockMvc
class OrderLifecycleHttpContractTest {
    @Autowired MockMvc mvc;
    @MockBean IVoucherOrderService orderService;
    @MockBean OrderLifecycleService lifecycle;
    @MockBean StringRedisTemplate redis;
    @MockBean HashOperations<String, Object, Object> hashes;
    @MockBean AccountAccessMapper accounts;

    @SpringBootConfiguration
    @EnableAutoConfiguration
    @Import({VoucherOrderController.class, GlobalExceptionHandler.class, SecurityConfig.class,
            SecurityErrorResponder.class, RedisTokenAuthenticationFilter.class, ResourceAuthorizationService.class})
    static class Application { }

    @Test
    void anonymousCannotReadOrCancel() throws Exception {
        mvc.perform(get("/voucher-order/100").param("voucherId", "9")).andExpect(status().isUnauthorized());
        mvc.perform(post("/voucher-order/100/cancel").param("voucherId", "9"))
                .andExpect(status().isUnauthorized()).andExpect(jsonPath("$.errorCode").value("AUTHENTICATION_FAILED"));
        verifyNoInteractions(lifecycle);
    }

    @Test
    void ownedReadReturnsStringIdentifiersAndOnlyTheResponseContract() throws Exception {
        session("USER");
        when(lifecycle.queryMine(9007199254740993L, 9L)).thenReturn(new OrderStatusResponse(
                "9007199254740993", "9", "PENDING_PAYMENT", null, null, null));
        mvc.perform(get("/voucher-order/9007199254740993").param("voucherId", "9").header("authorization", "phase3c-test"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.orderId").value("9007199254740993"))
                .andExpect(jsonPath("$.data.status").value("PENDING_PAYMENT"))
                .andExpect(jsonPath("$.data.userId").doesNotExist())
                .andExpect(jsonPath("$.data.payType").doesNotExist())
                .andExpect(jsonPath("$.data.payload").doesNotExist());
    }

    @Test
    void adminCannotUsePersonalOrderRoutesEvenWithUserRole() throws Exception {
        session("ADMIN", "USER");
        mvc.perform(get("/voucher-order/100").param("voucherId", "9").header("authorization", "phase3c-test"))
                .andExpect(status().isForbidden());
        mvc.perform(post("/voucher-order/100/cancel").param("voucherId", "9").header("authorization", "phase3c-test"))
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.errorCode").value("AUTHORIZATION_FAILED"));
        verifyNoInteractions(lifecycle);
    }

    @Test
    void merchantUsesPersonalRouteOnlyAsAnOrderOwner() throws Exception {
        session("MERCHANT");
        when(lifecycle.queryMine(100L, 9L)).thenThrow(new BusinessException(ErrorCode.NOT_FOUND));
        mvc.perform(get("/voucher-order/100").param("voucherId", "9").header("authorization", "phase3c-test"))
                .andExpect(status().isNotFound()).andExpect(jsonPath("$.errorCode").value("NOT_FOUND"));
    }

    @Test
    void missingMalformedAndNonPositiveParametersAreTyped400() throws Exception {
        session("USER");
        mvc.perform(get("/voucher-order/100").header("authorization", "phase3c-test"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.errorCode").value("VALIDATION_FAILED"));
        mvc.perform(get("/voucher-order/0").param("voucherId", "9").header("authorization", "phase3c-test"))
                .andExpect(status().isBadRequest());
        mvc.perform(post("/voucher-order/100/cancel").param("voucherId", "-1").header("authorization", "phase3c-test"))
                .andExpect(status().isBadRequest());
        mvc.perform(get("/voucher-order/not-a-number").param("voucherId", "9").header("authorization", "phase3c-test"))
                .andExpect(status().isBadRequest());
        verifyNoInteractions(lifecycle);
    }

    @Test
    void uncertainStateAndUnpersistedCancellationAreNotReportedAsSuccess() throws Exception {
        session("USER");
        when(lifecycle.queryMine(100L, 9L)).thenThrow(new BusinessException(ErrorCode.ORDER_STATE_UNAVAILABLE));
        when(lifecycle.cancelMine(100L, 9L)).thenThrow(new BusinessException(ErrorCode.CONFLICT));
        mvc.perform(get("/voucher-order/100").param("voucherId", "9").header("authorization", "phase3c-test"))
                .andExpect(status().isServiceUnavailable()).andExpect(jsonPath("$.errorCode").value("ORDER_STATE_UNAVAILABLE"));
        mvc.perform(post("/voucher-order/100/cancel").param("voucherId", "9").header("authorization", "phase3c-test"))
                .andExpect(status().isConflict());
    }

    @Test
    void cancellationResponseKeepsPendingCompensationDistinct() throws Exception {
        session("USER");
        when(lifecycle.cancelMine(100L, 9L)).thenReturn(new OrderStatusResponse("100", "9", "CANCELLED", "PENDING", null, null));
        mvc.perform(post("/voucher-order/100/cancel").param("voucherId", "9").header("authorization", "phase3c-test"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.status").value("CANCELLED"))
                .andExpect(jsonPath("$.data.cancellationCompensation").value("PENDING"));
    }

    @Test
    void disabledAccountCannotUsePersonalEndpoints() throws Exception {
        session("USER");
        when(accounts.findAccountStatus(7L)).thenReturn("DISABLED");
        mvc.perform(post("/voucher-order/100/cancel").param("voucherId", "9").header("authorization", "phase3c-test"))
                .andExpect(status().isUnauthorized());
        verifyNoInteractions(lifecycle);
    }

    private void session(String... roles) {
        Map<Object, Object> fields = new HashMap<>(); fields.put("id", "7"); fields.put("nickName", "Synthetic");
        when(redis.opsForHash()).thenReturn(hashes);
        when(hashes.entries(LOGIN_USER_KEY + "phase3c-test")).thenReturn(fields);
        when(accounts.findAccountStatus(7L)).thenReturn("ACTIVE");
        when(accounts.findRoles(7L)).thenReturn(Arrays.asList(roles));
    }
}
