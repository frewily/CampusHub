package io.github.frewily.campushub.service;

import io.github.frewily.campushub.entity.OrderCancellation;
import io.github.frewily.campushub.mapper.OrderCancellationMapper;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

class OrderCancellationReconcilerTest {
    private final OrderCancellationMapper entries = mock(OrderCancellationMapper.class);
    private final StringRedisTemplate redis = mock(StringRedisTemplate.class);
    private final OrderCancellationReconciler reconciler = new OrderCancellationReconciler(entries, redis, true);

    @Test
    void cannotReleaseIfAnotherWorkerOwnsLease() {
        reconciler.reconcile(entry());
        verifyNoInteractions(redis);
    }

    @Test
    void lastAttemptCrashEventuallyBecomesReviewWithoutRedisMutation() {
        reconciler.reconcile(entry().setAttempts(10));
        verify(entries).exhaust(100L);
        verifyNoInteractions(redis);
    }

    @Test
    void releaseReplayAndExpiryEachCompleteTheOutbox() {
        when(entries.claim(eq(100L), anyString())).thenReturn(1);
        for (int result = 0; result <= 2; result++) {
            when(redis.execute(any(org.springframework.data.redis.core.script.RedisScript.class), anyList(),
                    anyString(), anyString(), anyString())).thenReturn((long) result);
            reconciler.reconcile(entry());
            verify(entries).complete(eq(100L), anyString(), eq(new String[]{"RELEASED", "ALREADY_RELEASED", "EXPIRED"}[result]));
        }
    }

    @Test
    void uncertainRedisResultRetriesButNeverReversesDatabaseCancellation() {
        when(entries.claim(eq(100L), anyString())).thenReturn(1);
        when(redis.execute(any(org.springframework.data.redis.core.script.RedisScript.class), anyList(),
                anyString(), anyString(), anyString())).thenThrow(new IllegalStateException("secret must not be persisted"));
        reconciler.reconcile(entry());
        verify(entries).retry(eq(100L), anyString(), eq("IllegalStateException"));
        verify(entries, never()).complete(anyLong(), anyString(), anyString());
    }

    @Test
    void invalidOrUnmatchedStateRequiresReviewInsteadOfOverwritingActivity() {
        when(entries.claim(eq(100L), anyString())).thenReturn(1);
        when(redis.execute(any(org.springframework.data.redis.core.script.RedisScript.class), anyList(),
                anyString(), anyString(), anyString())).thenReturn(4L);
        reconciler.reconcile(entry());
        verify(entries).review(eq(100L), anyString(), eq("UnmatchedReservation"));
        verify(entries, never()).retry(anyLong(), anyString(), anyString());
    }

    @Test
    void incompleteLuaExecutionRequiresReviewNotCompletionOrAnotherAutomaticRelease() {
        when(entries.claim(eq(100L), anyString())).thenReturn(1);
        when(redis.execute(any(org.springframework.data.redis.core.script.RedisScript.class), anyList(),
                anyString(), anyString(), anyString())).thenReturn(3L);
        reconciler.reconcile(entry());
        verify(entries).review(eq(100L), anyString(), eq("InvalidRedisState"));
        verify(entries, never()).complete(anyLong(), anyString(), anyString());
        verify(entries, never()).retry(anyLong(), anyString(), anyString());
    }

    @Test
    void failedCompletionWriteLeavesLeaseToExpireForAnIdempotentRetry() {
        when(entries.claim(eq(100L), anyString())).thenReturn(1);
        when(redis.execute(any(org.springframework.data.redis.core.script.RedisScript.class), anyList(),
                anyString(), anyString(), anyString())).thenReturn(0L);
        when(entries.complete(eq(100L), anyString(), anyString())).thenThrow(new IllegalStateException("DB down"));
        assertThrows(IllegalStateException.class, () -> reconciler.reconcile(entry()));
        verify(entries, never()).retry(anyLong(), anyString(), anyString());
    }

    @Test
    void disabledWorkerDoesNotTouchAnyExternalService() {
        new OrderCancellationReconciler(entries, redis, false).reconcileDue();
        verifyNoInteractions(entries, redis);
    }

    private OrderCancellation entry() {
        return new OrderCancellation().setOrderId(100L).setUserId(7L).setVoucherId(9L)
                .setExpiresAtMs(1790000000000L).setAttempts(0);
    }
}
