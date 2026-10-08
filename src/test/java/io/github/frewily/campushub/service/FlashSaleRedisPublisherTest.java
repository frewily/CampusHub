package io.github.frewily.campushub.service;

import io.github.frewily.campushub.entity.Voucher;
import io.github.frewily.campushub.exception.BusinessException;
import io.github.frewily.campushub.exception.ErrorCode;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.time.LocalDateTime;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class FlashSaleRedisPublisherTest {
    @Mock StringRedisTemplate redis;
    private FlashSaleRedisPublisher publisher;

    @BeforeEach
    void setUp() {
        publisher = new FlashSaleRedisPublisher(redis);
        TransactionSynchronizationManager.initSynchronization();
        TransactionSynchronizationManager.setActualTransactionActive(true);
    }

    @AfterEach
    void clearTransaction() { TransactionSynchronizationManager.clear(); }

    @Test
    void registersOnlyUntilCommitAndPublishesOnceAfterCommit() {
        when(execute()).thenReturn(0L);
        publisher.publishAfterCommit(voucher());
        verifyNoInteractions(redis);
        assertEquals(1, TransactionSynchronizationManager.getSynchronizations().size());
        commit();
        verify(redis).execute(any(RedisScript.class), eq(FlashSaleRules.activityKeys(9L)),
                eq("3"), anyString(), anyString(), anyString());
    }

    @Test
    void rollbackDoesNotPublish() {
        publisher.publishAfterCommit(voucher());
        for (TransactionSynchronization synchronization : TransactionSynchronizationManager.getSynchronizations()) {
            synchronization.afterCompletion(TransactionSynchronization.STATUS_ROLLED_BACK);
        }
        verifyNoInteractions(redis);
    }

    @Test
    void duplicateKeysDoNotGetResetAndAreReportedAsSaved() {
        when(execute()).thenReturn(1L);
        publisher.publishAfterCommit(voucher());
        BusinessException error = assertThrows(BusinessException.class, this::commit);
        assertEquals(ErrorCode.ACTIVITY_UNAVAILABLE, error.getErrorCode());
        assertTrue(error.getMessage().contains("已保存"));
    }

    @Test
    void redisTimeoutAfterDatabaseCommitIsUncertain() {
        when(execute()).thenThrow(new RedisConnectionFailureException("timeout"));
        publisher.publishAfterCommit(voucher());
        assertEquals(ErrorCode.ACTIVITY_UNAVAILABLE,
                assertThrows(BusinessException.class, this::commit).getErrorCode());
    }

    @Test
    void requiresActiveTransactionAndValidInitializationParameters() {
        TransactionSynchronizationManager.setActualTransactionActive(false);
        assertThrows(IllegalStateException.class, () -> publisher.publishAfterCommit(voucher()));
        TransactionSynchronizationManager.setActualTransactionActive(true);
        assertEquals(ErrorCode.VALIDATION_FAILED,
                assertThrows(BusinessException.class, () -> publisher.publishAfterCommit(voucher().setStock(0)))
                        .getErrorCode());
        verifyNoInteractions(redis);
    }

    private void commit() {
        for (TransactionSynchronization synchronization : TransactionSynchronizationManager.getSynchronizations()) {
            synchronization.afterCommit();
        }
    }

    @SuppressWarnings("unchecked")
    private Long execute() {
        return redis.execute(any(RedisScript.class), anyList(), anyString(), anyString(), anyString(), anyString());
    }

    private Voucher voucher() {
        return new Voucher().setId(9L).setType(1).setStatus(1).setStock(3)
                .setBeginTime(LocalDateTime.of(2027, 1, 1, 10, 0))
                .setEndTime(LocalDateTime.of(2027, 1, 1, 11, 0));
    }
}
