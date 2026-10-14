package io.github.frewily.campushub.service;

import io.github.frewily.campushub.entity.BlogFeedDelivery;
import io.github.frewily.campushub.exception.BusinessException;
import io.github.frewily.campushub.exception.ErrorCode;
import io.github.frewily.campushub.mapper.BlogFeedDeliveryMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;

import static io.github.frewily.campushub.utils.RedisConstants.FEED_KEY;

/** Durable, immutable per-recipient intents; Redis replay uses the original score. */
@Slf4j
@Service
public class BlogFeedDeliveryService {
    private static final int FAST_PATH_LIMIT = 32;
    private final BlogFeedDeliveryMapper entries;
    private final StringRedisTemplate redis;
    private final boolean enabled;

    public BlogFeedDeliveryService(BlogFeedDeliveryMapper entries, StringRedisTemplate redis,
            @Value("${campushub.feed.delivery-worker-enabled:true}") boolean enabled) {
        this.entries = entries;
        this.redis = redis;
        this.enabled = enabled;
    }

    public static void requireWritableTransaction() {
        if (!TransactionSynchronizationManager.isActualTransactionActive()
                || !TransactionSynchronizationManager.isSynchronizationActive()
                || TransactionSynchronizationManager.isCurrentTransactionReadOnly()) {
            throw new IllegalStateException("Blog Feed intents require an active writable transaction");
        }
    }

    public void enqueue(Long blogId, List<Long> recipients, long scoreMillis) {
        requireWritableTransaction();
        if (blogId == null || blogId <= 0 || recipients == null || !validScore(scoreMillis)) {
            throw new BusinessException(ErrorCode.OPERATION_FAILED);
        }
        for (Long recipient : recipients) {
            if (recipient == null || recipient <= 0) throw new BusinessException(ErrorCode.OPERATION_FAILED);
        }
        List<BlogFeedDelivery> batch = new ArrayList<>();
        for (Long recipient : new LinkedHashSet<>(recipients)) {
            if (entries.enqueue(blogId, recipient, scoreMillis) != 1) {
                throw new BusinessException(ErrorCode.OPERATION_FAILED);
            }
            batch.add(new BlogFeedDelivery().setBlogId(blogId).setRecipientUserId(recipient).setScoreMillis(scoreMillis));
        }
        if (batch.isEmpty()) return;
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override public void afterCommit() {
                // Redis only: this callback's still-bound JDBC connection must never acknowledge the outbox.
                for (int i = 0; i < Math.min(batch.size(), FAST_PATH_LIMIT); i++) {
                    BlogFeedDelivery entry = batch.get(i);
                    try { deliver(entry); }
                    catch (RuntimeException failure) { logEntryFailure(entry, failure); }
                }
            }
        });
    }

    @Scheduled(fixedDelay = 5000)
    public void recoverPending() {
        if (!enabled) return;
        try {
            for (BlogFeedDelivery entry : entries.pending()) {
                try { recover(entry); }
                catch (RuntimeException failure) { logEntryFailure(entry, failure); }
            }
        } catch (RuntimeException failure) {
            log.warn("Blog Feed outbox poll unavailable, errorClass={}", safeErrorClass(failure));
        }
    }

    public void recover(BlogFeedDelivery entry) {
        try { deliver(entry); }
        catch (RuntimeException failure) {
            entries.failed(entry.getBlogId(), entry.getRecipientUserId(), entry.getScoreMillis(), safeErrorClass(failure));
            return;
        }
        // Duplicate workers may both ZADD the same member/score; only a matching intent can be deleted.
        int rows = entries.complete(entry.getBlogId(), entry.getRecipientUserId(), entry.getScoreMillis());
        if (rows != 0 && rows != 1) {
            log.warn("Blog Feed outbox completion unconfirmed, blogId={}, recipientId={}",
                    entry.getBlogId(), entry.getRecipientUserId());
        }
    }

    private void deliver(BlogFeedDelivery entry) {
        if (entry == null || entry.getBlogId() == null || entry.getBlogId() <= 0
                || entry.getRecipientUserId() == null || entry.getRecipientUserId() <= 0
                || entry.getScoreMillis() == null || !validScore(entry.getScoreMillis())) {
            throw new IllegalStateException("Invalid Blog Feed intent");
        }
        Boolean added = redis.opsForZSet().add(FEED_KEY + entry.getRecipientUserId(),
                entry.getBlogId().toString(), entry.getScoreMillis().doubleValue());
        // false means the existing member was confirmed too; null cannot confirm a write.
        if (added == null) throw new IllegalStateException("Unconfirmed Blog Feed write");
    }

    private static boolean validScore(long score) { return score > 0 && score <= 9007199254740991L; }

    private static String safeErrorClass(RuntimeException failure) {
        String name = failure.getClass().getSimpleName();
        if (name.isEmpty()) name = "RuntimeException";
        return name.length() <= 64 ? name : name.substring(0, 64);
    }

    private static void logEntryFailure(BlogFeedDelivery entry, RuntimeException failure) {
        log.warn("Blog Feed delivery pending, blogId={}, recipientId={}, errorClass={}",
                entry == null ? null : entry.getBlogId(), entry == null ? null : entry.getRecipientUserId(),
                safeErrorClass(failure));
    }
}
