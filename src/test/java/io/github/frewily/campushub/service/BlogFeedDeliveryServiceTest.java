package io.github.frewily.campushub.service;

import io.github.frewily.campushub.entity.BlogFeedDelivery;
import io.github.frewily.campushub.exception.BusinessException;
import io.github.frewily.campushub.exception.ErrorCode;
import io.github.frewily.campushub.mapper.BlogFeedDeliveryMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.config.YamlPropertiesFactoryBean;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ZSetOperations;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class BlogFeedDeliveryServiceTest {
    private static final long BLOG_ID = 41L;
    private static final long SCORE = 1_735_000_123_456L;
    private static final long MAX_EXACT_SCORE = 9_007_199_254_740_991L;

    private final BlogFeedDeliveryMapper entries = mock(BlogFeedDeliveryMapper.class);
    private final StringRedisTemplate redis = mock(StringRedisTemplate.class);
    @SuppressWarnings("unchecked")
    private final ZSetOperations<String, String> zset = mock(ZSetOperations.class);
    private final BlogFeedDeliveryService service = new BlogFeedDeliveryService(entries, redis, true);

    @AfterEach
    void clearTransactionState() {
        TransactionSynchronizationManager.clear();
    }

    private void beginWritableTransaction() {
        TransactionSynchronizationManager.initSynchronization();
        TransactionSynchronizationManager.setActualTransactionActive(true);
        when(entries.enqueue(org.mockito.ArgumentMatchers.anyLong(),
                org.mockito.ArgumentMatchers.anyLong(), org.mockito.ArgumentMatchers.anyLong())).thenReturn(1);
    }

    private void deliverToRedis() {
        when(redis.opsForZSet()).thenReturn(zset);
        when(zset.add(org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.anyDouble())).thenReturn(Boolean.TRUE);
    }

    private void runAfterCommit() {
        TransactionSynchronizationManager.getSynchronizations().forEach(TransactionSynchronization::afterCommit);
    }

    private BlogFeedDelivery delivery(long recipientId) {
        return new BlogFeedDelivery().setBlogId(BLOG_ID).setRecipientUserId(recipientId).setScoreMillis(SCORE);
    }

    @Test
    void requiresAnActualTransactionSynchronizationAndWritableTransaction() {
        TransactionSynchronizationManager.initSynchronization();
        assertThrows(IllegalStateException.class, () -> service.enqueue(BLOG_ID, Collections.singletonList(7L), SCORE));
        verifyNoInteractions(entries, redis);

        TransactionSynchronizationManager.clear();
        TransactionSynchronizationManager.setActualTransactionActive(true);
        assertThrows(IllegalStateException.class, () -> service.enqueue(BLOG_ID, Collections.singletonList(7L), SCORE));
        verifyNoInteractions(entries, redis);

        TransactionSynchronizationManager.initSynchronization();
        TransactionSynchronizationManager.setActualTransactionActive(true);
        TransactionSynchronizationManager.setCurrentTransactionReadOnly(true);
        assertThrows(IllegalStateException.class, () -> service.enqueue(BLOG_ID, Collections.singletonList(7L), SCORE));
        verifyNoInteractions(entries, redis);
    }

    @Test
    void validatesEveryArgumentBeforeWritingAnyOutboxRow() {
        beginWritableTransaction();
        assertOperationFailed(null, Collections.singletonList(7L), SCORE);
        assertOperationFailed(0L, Collections.singletonList(7L), SCORE);
        assertOperationFailed(-1L, Collections.singletonList(7L), SCORE);
        assertOperationFailed(BLOG_ID, null, SCORE);
        assertOperationFailed(BLOG_ID, Collections.singletonList(7L), 0L);
        assertOperationFailed(BLOG_ID, Collections.singletonList(7L), -1L);
        assertOperationFailed(BLOG_ID, Collections.singletonList(7L), MAX_EXACT_SCORE + 1);
        assertOperationFailed(BLOG_ID, Arrays.asList(7L, null), SCORE);
        assertOperationFailed(BLOG_ID, Arrays.asList(7L, 0L), SCORE);
        assertOperationFailed(BLOG_ID, Arrays.asList(7L, -2L), SCORE);
        verifyNoInteractions(entries, redis);
        assertTrue(TransactionSynchronizationManager.getSynchronizations().isEmpty());
    }

    private void assertOperationFailed(Long blogId, List<Long> recipients, long score) {
        BusinessException error = assertThrows(BusinessException.class,
                () -> service.enqueue(blogId, recipients, score));
        assertEquals(ErrorCode.OPERATION_FAILED, error.getErrorCode());
    }

    @Test
    void acceptsTheLargestExactlyRepresentableRedisScore() {
        beginWritableTransaction();
        deliverToRedis();
        service.enqueue(BLOG_ID, Collections.singletonList(7L), MAX_EXACT_SCORE);
        runAfterCommit();
        verify(zset).add("feed:7", Long.toString(BLOG_ID), (double) MAX_EXACT_SCORE);
    }

    @Test
    void emptyRecipientsDoNotWriteOrRegisterACommitCallback() {
        beginWritableTransaction();
        service.enqueue(BLOG_ID, Collections.emptyList(), SCORE);
        assertTrue(TransactionSynchronizationManager.getSynchronizations().isEmpty());
        verifyNoInteractions(entries, redis);
    }

    @Test
    void deduplicatesRecipientsInFirstSeenOrder() {
        beginWritableTransaction();
        service.enqueue(BLOG_ID, Arrays.asList(9L, 4L, 9L, 12L, 4L), SCORE);
        org.mockito.InOrder order = inOrder(entries);
        order.verify(entries).enqueue(BLOG_ID, 9L, SCORE);
        order.verify(entries).enqueue(BLOG_ID, 4L, SCORE);
        order.verify(entries).enqueue(BLOG_ID, 12L, SCORE);
        verify(entries, org.mockito.Mockito.times(3)).enqueue(org.mockito.ArgumentMatchers.eq(BLOG_ID), org.mockito.ArgumentMatchers.anyLong(),
                org.mockito.ArgumentMatchers.eq(SCORE));
        assertEquals(1, TransactionSynchronizationManager.getSynchronizations().size());
        verifyNoInteractions(redis);
    }

    @Test
    void zeroOutboxInsertFailsAndDoesNotRegisterCallback() {
        beginWritableTransaction();
        when(entries.enqueue(BLOG_ID, 7L, SCORE)).thenReturn(0);
        BusinessException error = assertThrows(BusinessException.class,
                () -> service.enqueue(BLOG_ID, Collections.singletonList(7L), SCORE));
        assertEquals(ErrorCode.OPERATION_FAILED, error.getErrorCode());
        assertTrue(TransactionSynchronizationManager.getSynchronizations().isEmpty());
        verifyNoInteractions(redis);
    }

    @Test
    void mapperExceptionPropagatesBeforeCallbackRegistration() {
        beginWritableTransaction();
        when(entries.enqueue(BLOG_ID, 7L, SCORE)).thenThrow(new IllegalStateException("database unavailable"));
        assertThrows(IllegalStateException.class,
                () -> service.enqueue(BLOG_ID, Collections.singletonList(7L), SCORE));
        assertTrue(TransactionSynchronizationManager.getSynchronizations().isEmpty());
        verifyNoInteractions(redis);
    }

    @Test
    void laterOutboxFailureStopsBatchBeforeAnyCommitCallbackIsRegistered() {
        beginWritableTransaction();
        when(entries.enqueue(BLOG_ID, 8L, SCORE)).thenReturn(0);
        assertThrows(BusinessException.class, () -> service.enqueue(BLOG_ID, Arrays.asList(7L, 8L), SCORE));
        verify(entries).enqueue(BLOG_ID, 7L, SCORE);
        verify(entries).enqueue(BLOG_ID, 8L, SCORE);
        assertTrue(TransactionSynchronizationManager.getSynchronizations().isEmpty());
        verifyNoInteractions(redis);
    }

    @Test
    void rollbackCompletionDoesNotInvokeTheAfterCommitCallback() {
        beginWritableTransaction();
        service.enqueue(BLOG_ID, Arrays.asList(7L, 8L), SCORE);
        TransactionSynchronizationManager.getSynchronizations().forEach(
                sync -> sync.afterCompletion(TransactionSynchronization.STATUS_ROLLED_BACK));
        verifyNoInteractions(redis);
        verify(entries).enqueue(BLOG_ID, 7L, SCORE);
        verify(entries).enqueue(BLOG_ID, 8L, SCORE);
    }

    @Test
    void redisIsUntouchedBeforeCommitAndCallbackOnlyAddsEachRecipientAtOriginalScore() {
        beginWritableTransaction();
        deliverToRedis();
        service.enqueue(BLOG_ID, Arrays.asList(7L, 8L), SCORE);
        verifyNoInteractions(redis);

        clearInvocations(entries);
        runAfterCommit();

        org.mockito.InOrder order = inOrder(zset);
        order.verify(zset).add("feed:7", "41", (double) SCORE);
        order.verify(zset).add("feed:8", "41", (double) SCORE);
        verifyNoInteractions(entries);
    }

    @Test
    void afterCommitFastPathCapsRedisWritesAt32AndLeavesRemainingIntentPending() {
        beginWritableTransaction();
        deliverToRedis();
        List<Long> recipients = new java.util.ArrayList<>();
        for (long recipientId = 1; recipientId <= 33; recipientId++) recipients.add(recipientId);

        service.enqueue(BLOG_ID, recipients, SCORE);

        verify(entries, org.mockito.Mockito.times(33)).enqueue(org.mockito.ArgumentMatchers.eq(BLOG_ID),
                org.mockito.ArgumentMatchers.anyLong(), org.mockito.ArgumentMatchers.eq(SCORE));
        assertEquals(1, TransactionSynchronizationManager.getSynchronizations().size());
        verifyNoInteractions(redis);

        clearInvocations(entries);
        runAfterCommit();

        verify(zset, org.mockito.Mockito.times(32)).add(org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.eq(Long.toString(BLOG_ID)),
                org.mockito.ArgumentMatchers.eq((double) SCORE));
        verify(zset, never()).add("feed:33", Long.toString(BLOG_ID), (double) SCORE);
        verifyNoInteractions(entries);
    }

    @Test
    void oneRecipientRedisFailureDoesNotBlockLaterRecipientsOrTouchOutboxInCallback() {
        beginWritableTransaction();
        when(redis.opsForZSet()).thenReturn(zset);
        when(zset.add("feed:7", "41", (double) SCORE)).thenThrow(new IllegalStateException("private detail"));
        when(zset.add("feed:8", "41", (double) SCORE)).thenReturn(Boolean.TRUE);
        service.enqueue(BLOG_ID, Arrays.asList(7L, 8L), SCORE);
        clearInvocations(entries);

        assertDoesNotThrow(this::runAfterCommit);

        verify(zset).add("feed:7", "41", (double) SCORE);
        verify(zset).add("feed:8", "41", (double) SCORE);
        verifyNoInteractions(entries);
    }

    @Test
    void falseRedisResultIsAnIdempotentConfirmation() {
        beginWritableTransaction();
        when(redis.opsForZSet()).thenReturn(zset);
        when(zset.add("feed:7", "41", (double) SCORE)).thenReturn(Boolean.FALSE);
        service.enqueue(BLOG_ID, Collections.singletonList(7L), SCORE);
        clearInvocations(entries);
        runAfterCommit();
        verify(zset).add("feed:7", "41", (double) SCORE);
        verifyNoInteractions(entries);
    }

    @Test
    void nullRedisResultLeavesIntentForRecoveryWithoutDbAcknowledgement() {
        beginWritableTransaction();
        when(redis.opsForZSet()).thenReturn(zset);
        when(zset.add("feed:7", "41", (double) SCORE)).thenReturn(null);
        service.enqueue(BLOG_ID, Collections.singletonList(7L), SCORE);
        clearInvocations(entries);
        assertDoesNotThrow(this::runAfterCommit);
        verifyNoInteractions(entries);
    }

    @Test
    void recoveryPollIsDisabledWithoutQueryingMapper() {
        YamlPropertiesFactoryBean testProfile = new YamlPropertiesFactoryBean();
        testProfile.setResources(new ClassPathResource("application-test.yaml"));
        String enabled = testProfile.getObject().getProperty("campushub.feed.delivery-worker-enabled");
        assertEquals("false", enabled, "offline test profile must not start the new DB polling worker");
        new BlogFeedDeliveryService(entries, redis, Boolean.parseBoolean(enabled)).recoverPending();
        verifyNoInteractions(entries, redis);
    }

    @Test
    void pollFailureIsSwallowed() {
        when(entries.pending()).thenThrow(new IllegalStateException("database unavailable"));
        assertDoesNotThrow(service::recoverPending);
        verify(entries).pending();
        verifyNoInteractions(redis);
    }

    @Test
    void oneRecoveryFailureDoesNotPreventLaterEntriesFromCompleting() {
        when(entries.pending()).thenReturn(Arrays.asList(delivery(7L), delivery(8L)));
        when(redis.opsForZSet()).thenReturn(zset);
        when(zset.add("feed:7", "41", (double) SCORE)).thenReturn(null);
        when(zset.add("feed:8", "41", (double) SCORE)).thenReturn(Boolean.FALSE);
        when(entries.failed(BLOG_ID, 7L, SCORE, "IllegalStateException")).thenThrow(
                new IllegalStateException("database confirmation unavailable"));
        when(entries.complete(BLOG_ID, 8L, SCORE)).thenReturn(1);

        assertDoesNotThrow(service::recoverPending);

        verify(entries).failed(BLOG_ID, 7L, SCORE, "IllegalStateException");
        verify(entries).complete(BLOG_ID, 8L, SCORE);
    }

    @Test
    void recoveryRedisFailureRecordsOnlySafeExceptionClassWithCasKey() {
        BlogFeedDelivery entry = delivery(7L);
        when(redis.opsForZSet()).thenReturn(zset);
        when(zset.add("feed:7", "41", (double) SCORE)).thenThrow(
                new IllegalArgumentException("do not persist this message"));
        when(entries.failed(BLOG_ID, 7L, SCORE, "IllegalArgumentException")).thenReturn(1);

        service.recover(entry);

        verify(entries).failed(BLOG_ID, 7L, SCORE, "IllegalArgumentException");
        verify(entries, never()).complete(BLOG_ID, 7L, SCORE);
    }

    @Test
    void recoveryAcceptsFalseAsConfirmedAndCompletesWithTheSameCasKey() {
        BlogFeedDelivery entry = delivery(7L);
        when(redis.opsForZSet()).thenReturn(zset);
        when(zset.add("feed:7", "41", (double) SCORE)).thenReturn(Boolean.FALSE);
        when(entries.complete(BLOG_ID, 7L, SCORE)).thenReturn(1);

        service.recover(entry);

        verify(entries).complete(BLOG_ID, 7L, SCORE);
        verify(entries, never()).failed(org.mockito.ArgumentMatchers.anyLong(),
                org.mockito.ArgumentMatchers.anyLong(), org.mockito.ArgumentMatchers.anyLong(),
                org.mockito.ArgumentMatchers.anyString());
    }

    @Test
    void recoveryNullResultRecordsFailureAndNeverCompletes() {
        BlogFeedDelivery entry = delivery(7L);
        when(redis.opsForZSet()).thenReturn(zset);
        when(zset.add("feed:7", "41", (double) SCORE)).thenReturn(null);
        when(entries.failed(BLOG_ID, 7L, SCORE, "IllegalStateException")).thenReturn(1);

        service.recover(entry);

        verify(entries).failed(BLOG_ID, 7L, SCORE, "IllegalStateException");
        verify(entries, never()).complete(BLOG_ID, 7L, SCORE);
    }

    @Test
    void recoveryCompletionExceptionPropagatesForPollLevelIsolation() {
        BlogFeedDelivery entry = delivery(7L);
        when(redis.opsForZSet()).thenReturn(zset);
        when(zset.add("feed:7", "41", (double) SCORE)).thenReturn(Boolean.TRUE);
        when(entries.complete(BLOG_ID, 7L, SCORE)).thenThrow(new IllegalStateException("retry later"));

        assertThrows(IllegalStateException.class, () -> service.recover(entry));
        verify(entries).complete(BLOG_ID, 7L, SCORE);
        verify(entries, never()).failed(org.mockito.ArgumentMatchers.anyLong(),
                org.mockito.ArgumentMatchers.anyLong(), org.mockito.ArgumentMatchers.anyLong(),
                org.mockito.ArgumentMatchers.anyString());
    }
}
