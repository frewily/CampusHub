package io.github.frewily.campushub.service;

import io.github.frewily.campushub.entity.ShopCacheInvalidation;
import io.github.frewily.campushub.exception.BusinessException;
import io.github.frewily.campushub.exception.ErrorCode;
import io.github.frewily.campushub.mapper.ShopCacheInvalidationMapper;
import io.github.frewily.campushub.utils.CacheClient;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import java.util.UUID;

@Slf4j
@Service
public class ShopCacheInvalidationService {
    private final ShopCacheInvalidationMapper entries;
    private final CacheClient cache;
    private final boolean enabled;
    public ShopCacheInvalidationService(ShopCacheInvalidationMapper entries, CacheClient cache,
            @Value("${campushub.cache.invalidation-worker-enabled:true}") boolean enabled) {
        this.entries = entries; this.cache = cache; this.enabled = enabled;
    }

    /** MUST run in the same actual transaction as the shop mutation. No Redis before commit. */
    public void enqueue(Long id) {
        if (!TransactionSynchronizationManager.isActualTransactionActive()
                || !TransactionSynchronizationManager.isSynchronizationActive()) {
            throw new IllegalStateException("Shop cache invalidation requires an active transaction");
        }
        if (id == null || id <= 0) throw new BusinessException(ErrorCode.SHOP_STATE_UNAVAILABLE);
        if (entries.enqueue(id, UUID.randomUUID().toString()) < 1) {
            throw new BusinessException(ErrorCode.SHOP_STATE_UNAVAILABLE);
        }
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override public void afterCommit() {
                // NEVER use this callback's still-bound DB connection to mark a row complete.
                // Redis only; scheduled recovery acknowledges via a new committed DB operation.
                try { cache.invalidateShop(id); }
                catch (RuntimeException error) {
                    log.warn("Shop cache invalidation pending, shopId={}, errorClass={}", id, error.getClass().getSimpleName());
                }
            }
        });
    }

    @Scheduled(fixedDelay = 5000)
    public void recoverPending() {
        if (!enabled) return;
        try {
            for (ShopCacheInvalidation entry : entries.pending()) {
                try { recover(entry); }
                catch (RuntimeException error) {
                    log.warn("Shop invalidation unavailable, shopId={}, errorClass={}", entry.getShopId(), error.getClass().getSimpleName());
                }
            }
        } catch (RuntimeException error) {
            log.warn("Shop invalidation outbox unavailable, errorClass={}", error.getClass().getSimpleName());
        }
    }
    public void recover(ShopCacheInvalidation entry) {
        try { cache.invalidateShop(entry.getShopId()); }
        catch (RuntimeException error) {
            entries.failed(entry.getShopId(), entry.getGeneration(), error.getClass().getSimpleName());
            return;
        }
        // A newer DB write replaces generation; an old worker cannot erase that pending event.
        entries.complete(entry.getShopId(), entry.getGeneration());
    }
}
