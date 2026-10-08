package io.github.frewily.campushub.service.impl;

import io.github.frewily.campushub.entity.SeckillVoucher;
import io.github.frewily.campushub.entity.Voucher;
import io.github.frewily.campushub.exception.BusinessException;
import io.github.frewily.campushub.mapper.VoucherMapper;
import io.github.frewily.campushub.service.FlashSaleRedisPublisher;
import io.github.frewily.campushub.service.ISeckillVoucherService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.LocalDateTime;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class VoucherServicePublicationTest {
    private VoucherMapper vouchers;
    private ISeckillVoucherService stock;
    private FlashSaleRedisPublisher publisher;
    private VoucherServiceImpl service;

    @BeforeEach
    void setUp() {
        vouchers = mock(VoucherMapper.class);
        stock = mock(ISeckillVoucherService.class);
        publisher = mock(FlashSaleRedisPublisher.class);
        service = new VoucherServiceImpl();
        ReflectionTestUtils.setField(service, "baseMapper", vouchers);
        ReflectionTestUtils.setField(service, "seckillVoucherService", stock);
        ReflectionTestUtils.setField(service, "flashSaleRedisPublisher", publisher);
    }

    @Test
    void publishesOnlyAfterBothRowsWereSavedAndTimesNormalized() {
        when(vouchers.insert(any())).thenReturn(1);
        when(stock.save(any(SeckillVoucher.class))).thenReturn(true);
        Voucher voucher = voucher();
        service.addSeckillVoucher(voucher);
        InOrder order = inOrder(vouchers, stock, publisher);
        order.verify(vouchers).insert(voucher);
        order.verify(stock).save(any(SeckillVoucher.class));
        order.verify(publisher).publishAfterCommit(voucher);
        assertEquals(0, voucher.getBeginTime().getNano());
        assertEquals(0, voucher.getEndTime().getNano());
    }

    @Test
    void voucherInsertFailureDoesNotPublish() {
        assertThrows(BusinessException.class, () -> service.addSeckillVoucher(voucher()));
        verifyNoInteractions(stock, publisher);
    }

    @Test
    void stockInsertFailureDoesNotPublish() {
        when(vouchers.insert(any())).thenReturn(1);
        assertThrows(BusinessException.class, () -> service.addSeckillVoucher(voucher()));
        verifyNoInteractions(publisher);
    }

    private Voucher voucher() {
        return new Voucher().setType(1).setStatus(1).setStock(3)
                .setBeginTime(LocalDateTime.of(2027, 1, 1, 10, 0, 0, 800000000))
                .setEndTime(LocalDateTime.of(2027, 1, 1, 11, 0, 0, 800000000));
    }
}
