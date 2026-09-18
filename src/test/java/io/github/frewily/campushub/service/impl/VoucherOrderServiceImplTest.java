package io.github.frewily.campushub.service.impl;

import com.baomidou.mybatisplus.extension.conditions.update.UpdateChainWrapper;
import io.github.frewily.campushub.entity.SeckillVoucher;
import io.github.frewily.campushub.entity.VoucherOrder;
import io.github.frewily.campushub.mapper.VoucherOrderMapper;
import io.github.frewily.campushub.service.ISeckillVoucherService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class VoucherOrderServiceImplTest {

    @Mock
    private RedissonClient redissonClient;
    @Mock
    private RLock lock;
    @Mock
    private VoucherOrderMapper voucherOrderMapper;
    @Mock
    private ISeckillVoucherService seckillVoucherService;
    @Mock
    private UpdateChainWrapper<SeckillVoucher> stockUpdate;
    @Mock
    private TransactionTemplate transactionTemplate;
    @Mock
    private TransactionStatus transactionStatus;

    private VoucherOrderServiceImpl service;

    @AfterEach
    void tearDown() {
        if (service != null) {
            service.destroy();
        }
    }

    @Test
    void shouldPropagatePersistenceFailureSoStreamMessageStaysPending() {
        service = spy(new VoucherOrderServiceImpl());
        ReflectionTestUtils.setField(service, "redissonClient", redissonClient);
        VoucherOrder order = order();
        when(redissonClient.getLock("lock:order:7")).thenReturn(lock);
        when(lock.tryLock()).thenReturn(true);
        doThrow(new IllegalStateException("database unavailable"))
                .when(service).createVoucherOrder(order);

        assertThrows(IllegalStateException.class,
                () -> ReflectionTestUtils.invokeMethod(service, "handleVoucherOrder", order));
        verify(lock).unlock();
    }

    @Test
    void shouldTreatLockContentionAsRetryableFailure() {
        service = new VoucherOrderServiceImpl();
        ReflectionTestUtils.setField(service, "redissonClient", redissonClient);
        VoucherOrder order = order();
        when(redissonClient.getLock("lock:order:7")).thenReturn(lock);
        when(lock.tryLock()).thenReturn(false);

        assertThrows(IllegalStateException.class,
                () -> ReflectionTestUtils.invokeMethod(service, "handleVoucherOrder", order));
    }

    @Test
    void shouldFailInsteadOfAcknowledgingWhenDatabaseStockCannotBeDecremented() {
        prepareTransactionalPersistence();
        when(voucherOrderMapper.selectCount(any())).thenReturn(0);
        when(seckillVoucherService.update()).thenReturn(stockUpdate);
        when(stockUpdate.setSql("stock = stock - 1")).thenReturn(stockUpdate);
        when(stockUpdate.eq("voucher_id", 9L)).thenReturn(stockUpdate);
        when(stockUpdate.gt("stock", 0)).thenReturn(stockUpdate);
        when(stockUpdate.update()).thenReturn(false);

        assertThrows(IllegalStateException.class, () -> service.createVoucherOrder(order()));
        verify(transactionTemplate).executeWithoutResult(any());
    }

    @Test
    void shouldFailAndRollBackWhenOrderInsertDoesNotSucceed() {
        prepareTransactionalPersistence();
        when(voucherOrderMapper.selectCount(any())).thenReturn(0);
        when(seckillVoucherService.update()).thenReturn(stockUpdate);
        when(stockUpdate.setSql("stock = stock - 1")).thenReturn(stockUpdate);
        when(stockUpdate.eq("voucher_id", 9L)).thenReturn(stockUpdate);
        when(stockUpdate.gt("stock", 0)).thenReturn(stockUpdate);
        when(stockUpdate.update()).thenReturn(true);
        when(voucherOrderMapper.insert(any(VoucherOrder.class))).thenReturn(0);

        assertThrows(IllegalStateException.class, () -> service.createVoucherOrder(order()));
        verify(transactionTemplate).executeWithoutResult(any());
    }

    @Test
    void shouldTreatAnExistingOrderAsIdempotentSuccess() {
        prepareTransactionalPersistence();
        when(voucherOrderMapper.selectCount(any())).thenReturn(1);

        assertDoesNotThrow(() -> service.createVoucherOrder(order()));
        verify(seckillVoucherService, never()).update();
    }

    private void prepareTransactionalPersistence() {
        service = new VoucherOrderServiceImpl();
        ReflectionTestUtils.setField(service, "baseMapper", voucherOrderMapper);
        ReflectionTestUtils.setField(service, "seckillVoucherService", seckillVoucherService);
        ReflectionTestUtils.setField(service, "transactionTemplate", transactionTemplate);
        doAnswer(invocation -> {
            Consumer<TransactionStatus> action = invocation.getArgument(0);
            action.accept(transactionStatus);
            return null;
        }).when(transactionTemplate).executeWithoutResult(any());
    }

    private VoucherOrder order() {
        return new VoucherOrder().setId(100L).setUserId(7L).setVoucherId(9L);
    }
}
