package io.github.frewily.campushub.service;

import io.github.frewily.campushub.entity.ShopCacheInvalidation;
import io.github.frewily.campushub.mapper.ShopCacheInvalidationMapper;
import io.github.frewily.campushub.utils.CacheClient;
import io.github.frewily.campushub.exception.*;
import org.junit.jupiter.api.*;
import org.springframework.transaction.support.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class ShopCacheInvalidationServiceTest {
    private final ShopCacheInvalidationMapper entries = mock(ShopCacheInvalidationMapper.class);
    private final CacheClient cache = mock(CacheClient.class);
    private final ShopCacheInvalidationService service = new ShopCacheInvalidationService(entries,cache,true);
    @AfterEach void clear() { TransactionSynchronizationManager.clear(); }
    private void transaction() {
        TransactionSynchronizationManager.initSynchronization();
        TransactionSynchronizationManager.setActualTransactionActive(true);
        when(entries.enqueue(eq(1L),anyString())).thenReturn(1);
    }
    @Test void cannotEnqueueOutsideAnActualTransaction() {
        assertThrows(IllegalStateException.class,()->service.enqueue(1L)); verifyNoInteractions(entries,cache);
    }
    @Test void enqueueBeforeCommitOnlyTouchesOutboxAndRollbackDoesNotInvalidate() {
        transaction(); service.enqueue(1L); verify(entries).enqueue(eq(1L),anyString()); verifyNoInteractions(cache);
        TransactionSynchronizationManager.getSynchronizations().forEach(sync->sync.afterCompletion(TransactionSynchronization.STATUS_ROLLED_BACK));
        verifyNoInteractions(cache);
    }
    @Test void afterCommitOnlyCallsRedisAndNeverWritesThroughTheStillBoundDbTransaction() {
        transaction(); service.enqueue(1L); clearInvocations(entries);
        TransactionSynchronizationManager.getSynchronizations().forEach(TransactionSynchronization::afterCommit);
        verify(cache).invalidateShop(1L); verifyNoInteractions(entries);
        assertEquals(1, service.diagnostics().count(ShopCacheInvalidationService.DiagnosticEvent.CALLBACK_INVALIDATED));
    }
    @Test void postCommitFailureDoesNotPretendTheDbRolledBackAndKeepsOutbox() {
        transaction(); service.enqueue(1L);
        doThrow(new IllegalStateException("not persisted")).when(cache).invalidateShop(1L);
        assertDoesNotThrow(()->TransactionSynchronizationManager.getSynchronizations().forEach(TransactionSynchronization::afterCommit));
        verify(entries,never()).complete(anyLong(),anyString());
        assertEquals(1, service.diagnostics().count(ShopCacheInvalidationService.DiagnosticEvent.CALLBACK_FAILED));
        assertEquals(0, service.diagnostics().count(ShopCacheInvalidationService.DiagnosticEvent.CALLBACK_INVALIDATED));
    }
    @Test void enqueueFailureIsNotSwallowedAndCanRollbackTheShopWrite() {
        transaction(); when(entries.enqueue(anyLong(),anyString())).thenReturn(0);
        assertEquals(ErrorCode.SHOP_STATE_UNAVAILABLE,assertThrows(BusinessException.class,()->service.enqueue(1L)).getErrorCode());
        assertTrue(TransactionSynchronizationManager.getSynchronizations().isEmpty()); verifyNoInteractions(cache);
    }
    @Test void recoveryUsesCasAcknowledgementAndSafeErrorClass() {
        ShopCacheInvalidation entry=new ShopCacheInvalidation().setShopId(1L).setGeneration("event");
        when(entries.complete(1L,"event")).thenReturn(1);
        service.recover(entry); verify(entries).complete(1L,"event");
        assertEquals(1, service.diagnostics().count(ShopCacheInvalidationService.DiagnosticEvent.COMPLETED));
        clearInvocations(entries);
        doThrow(new IllegalStateException("secret must not persist")).when(cache).invalidateShop(1L);
        when(entries.failed(1L,"event","IllegalStateException")).thenReturn(1);
        service.recover(entry); verify(entries).failed(1L,"event","IllegalStateException");
        verify(entries,never()).complete(anyLong(),anyString());
        assertEquals(1, service.diagnostics().count(ShopCacheInvalidationService.DiagnosticEvent.RETRY_RECORDED));
    }
    @Test void staleCompletionAndDbConfirmationFailureAreNotCountedAsComplete() {
        ShopCacheInvalidation entry=new ShopCacheInvalidation().setShopId(1L).setGeneration("event");
        when(entries.complete(1L,"event")).thenReturn(0);
        service.recover(entry);
        assertEquals(0, service.diagnostics().count(ShopCacheInvalidationService.DiagnosticEvent.COMPLETED));
        assertEquals(1, service.diagnostics().count(ShopCacheInvalidationService.DiagnosticEvent.STALE));

        when(entries.complete(1L,"event")).thenThrow(new IllegalStateException("DB confirmation unavailable"));
        assertThrows(IllegalStateException.class, () -> service.recover(entry));
        assertEquals(0, service.diagnostics().count(ShopCacheInvalidationService.DiagnosticEvent.COMPLETED));
    }
    @Test void uncertainRedisCallbackIsOnlyCountedAsCallbackFailure() {
        transaction(); service.enqueue(1L);
        doThrow(new IllegalStateException("Redis result uncertain")).when(cache).invalidateShop(1L);
        TransactionSynchronizationManager.getSynchronizations().forEach(TransactionSynchronization::afterCommit);
        assertEquals(1, service.diagnostics().count(ShopCacheInvalidationService.DiagnosticEvent.CALLBACK_FAILED));
        assertEquals(0, service.diagnostics().count(ShopCacheInvalidationService.DiagnosticEvent.COMPLETED));
    }
    @Test void disabledRecoveryDoesNotAccessServices() {
        ShopCacheInvalidationService disabled = new ShopCacheInvalidationService(entries,cache,false);
        disabled.recoverPending(); verifyNoInteractions(entries,cache);
        assertEquals(0, disabled.diagnostics().count(ShopCacheInvalidationService.DiagnosticEvent.POLL_FAILURE));
    }
}
