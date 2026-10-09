package io.github.frewily.campushub.service;

import io.github.frewily.campushub.entity.OrderCancellation;
import io.github.frewily.campushub.entity.ShopCacheInvalidation;
import io.github.frewily.campushub.mapper.OrderCancellationMapper;
import io.github.frewily.campushub.mapper.ShopCacheInvalidationMapper;
import io.github.frewily.campushub.utils.CacheClient;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;

import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class BusinessRecoveryDiagnosticsTest {
    @Test void shopDbConfirmationFailureIsIsolatedAndNotCountedAsCompletion() {
        ShopCacheInvalidationMapper entries = mock(ShopCacheInvalidationMapper.class);
        CacheClient cache = mock(CacheClient.class);
        ShopCacheInvalidationService service = new ShopCacheInvalidationService(entries, cache, true);
        when(entries.pending()).thenReturn(Arrays.asList(
                new ShopCacheInvalidation().setShopId(1L).setGeneration("a"),
                new ShopCacheInvalidation().setShopId(2L).setGeneration("b")));
        when(entries.complete(1L, "a")).thenThrow(new IllegalStateException("synthetic failure"));
        when(entries.complete(2L, "b")).thenReturn(1);
        service.recoverPending();
        assertEquals(1, service.diagnostics().count(ShopCacheInvalidationService.DiagnosticEvent.ENTRY_FAILURE));
        assertEquals(1, service.diagnostics().count(ShopCacheInvalidationService.DiagnosticEvent.COMPLETED));
        verify(cache).invalidateShop(2L);
    }

    @Test void outboxPollFailuresDoNotInventWorkOrCompletion() {
        ShopCacheInvalidationMapper shops = mock(ShopCacheInvalidationMapper.class);
        OrderCancellationMapper orders = mock(OrderCancellationMapper.class);
        CacheClient cache = mock(CacheClient.class);
        StringRedisTemplate redis = mock(StringRedisTemplate.class);
        ShopCacheInvalidationService invalidations = new ShopCacheInvalidationService(shops, cache, true);
        OrderCancellationReconciler cancellations = new OrderCancellationReconciler(orders, redis, true);
        when(shops.pending()).thenThrow(new IllegalStateException("synthetic outage"));
        when(orders.due()).thenThrow(new IllegalStateException("synthetic outage"));
        invalidations.recoverPending(); cancellations.reconcileDue();
        assertEquals(1, invalidations.diagnostics().count(ShopCacheInvalidationService.DiagnosticEvent.POLL_FAILURE));
        assertEquals(0, invalidations.diagnostics().count(ShopCacheInvalidationService.DiagnosticEvent.COMPLETED));
        assertEquals(1, cancellations.diagnostics().count(OrderCancellationReconciler.DiagnosticEvent.POLL_FAILURE));
        assertEquals(0, cancellations.diagnostics().count(OrderCancellationReconciler.DiagnosticEvent.RELEASED));
        verifyNoInteractions(cache, redis);
    }

    @Test void cancellationDbConfirmationFailureDoesNotStarveTheNextEntry() {
        OrderCancellationMapper entries = mock(OrderCancellationMapper.class);
        StringRedisTemplate redis = mock(StringRedisTemplate.class);
        OrderCancellationReconciler service = new OrderCancellationReconciler(entries, redis, true);
        when(entries.due()).thenReturn(Arrays.asList(entry(1L), entry(2L)));
        when(entries.claim(anyLong(), anyString())).thenReturn(1);
        when(redis.execute(any(RedisScript.class), anyList(), anyString(), anyString(), anyString())).thenReturn(0L);
        when(entries.complete(eq(1L), anyString(), eq("RELEASED"))).thenThrow(new IllegalStateException("synthetic failure"));
        when(entries.complete(eq(2L), anyString(), eq("RELEASED"))).thenReturn(1);
        service.reconcileDue();
        assertEquals(1, service.diagnostics().count(OrderCancellationReconciler.DiagnosticEvent.ENTRY_FAILURE));
        assertEquals(1, service.diagnostics().count(OrderCancellationReconciler.DiagnosticEvent.RELEASED));
        verify(entries, never()).retry(anyLong(), anyString(), anyString());
    }

    private OrderCancellation entry(Long id) {
        return new OrderCancellation().setOrderId(id).setUserId(7L).setVoucherId(9L)
                .setExpiresAtMs(1790000000000L).setAttempts(0);
    }
}
