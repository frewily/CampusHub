package io.github.frewily.campushub.controller;

import io.github.frewily.campushub.config.GlobalExceptionHandler;
import io.github.frewily.campushub.dto.response.OrderAcceptanceResult;
import io.github.frewily.campushub.exception.BusinessException;
import io.github.frewily.campushub.exception.ErrorCode;
import io.github.frewily.campushub.service.IVoucherOrderService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.MockMvc;

import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import static org.springframework.test.web.servlet.setup.MockMvcBuilders.standaloneSetup;

class OrderAcceptanceHttpContractTest {
    private IVoucherOrderService orders;
    private MockMvc mvc;

    @BeforeEach
    void setup() {
        orders = Mockito.mock(IVoucherOrderService.class);
        VoucherOrderController controller = new VoucherOrderController();
        ReflectionTestUtils.setField(controller, "voucherOrderService", orders);
        mvc = standaloneSetup(controller).setControllerAdvice(new GlobalExceptionHandler()).build();
    }

    @Test
    void acceptedResponseKeepsNumericDataAndDeclaresReplay() throws Exception {
        when(orders.seckillVoucher(9L)).thenReturn(new OrderAcceptanceResult(100L, false),
                new OrderAcceptanceResult(100L, true));
        mvc.perform(post("/voucher-order/seckill/9")).andExpect(status().isOk())
                .andExpect(jsonPath("$.data").value(100)).andExpect(jsonPath("$.acceptanceStatus").value("ACCEPTED"))
                .andExpect(jsonPath("$.replayed").value(false));
        mvc.perform(post("/voucher-order/seckill/9")).andExpect(status().isOk())
                .andExpect(jsonPath("$.data").value(100)).andExpect(jsonPath("$.replayed").value(true));
    }

    @Test
    void acceptanceExposesLosslessIdWithoutRemovingLegacyNumericData() throws Exception {
        when(orders.seckillVoucher(9L)).thenReturn(new OrderAcceptanceResult(Long.MAX_VALUE, false));
        mvc.perform(post("/voucher-order/seckill/9")).andExpect(status().isOk())
                .andExpect(jsonPath("$.data").value(Long.MAX_VALUE))
                .andExpect(jsonPath("$.orderId").value("9223372036854775807"));
    }

    @Test
    void uncertainRedisFailureIsStructured503() throws Exception {
        when(orders.seckillVoucher(9L)).thenThrow(new BusinessException(ErrorCode.ACTIVITY_UNAVAILABLE));
        mvc.perform(post("/voucher-order/seckill/9")).andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.errorCode").value("ACTIVITY_UNAVAILABLE"));
    }
}
