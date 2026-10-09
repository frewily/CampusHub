package io.github.frewily.campushub.service.impl;

import io.github.frewily.campushub.entity.Shop;
import io.github.frewily.campushub.mapper.ShopMapper;
import io.github.frewily.campushub.mapper.ShopCacheInvalidationMapper;
import io.github.frewily.campushub.service.ShopCacheInvalidationService;
import io.github.frewily.campushub.utils.CacheClient;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import static org.mockito.Mockito.*;

class ShopCacheCommitBoundaryTest {
    @Test
    void updateMustNotInvalidateRedisBeforeCommitOrOnRollback() {
        ShopMapper mapper = mock(ShopMapper.class);
        StringRedisTemplate redis = mock(StringRedisTemplate.class);
        ShopServiceImpl service = new ShopServiceImpl();
        ReflectionTestUtils.setField(service, "baseMapper", mapper);
        ReflectionTestUtils.setField(service, "stringRedisTemplate", redis);
        ShopCacheInvalidationMapper outbox = mock(ShopCacheInvalidationMapper.class);
        CacheClient cache = mock(CacheClient.class);
        ReflectionTestUtils.setField(service, "shopCacheInvalidationService",
                new ShopCacheInvalidationService(outbox, cache, true));
        when(outbox.enqueue(eq(1L), anyString())).thenReturn(1);
        when(mapper.selectById(1L)).thenReturn(new Shop().setId(1L).setMerchantId(10L));
        when(mapper.updateById(any(Shop.class))).thenReturn(1);
        TransactionSynchronizationManager.initSynchronization();
        TransactionSynchronizationManager.setActualTransactionActive(true);
        try {
            service.updateShop(new Shop().setId(1L).setName("updated"));
            verifyNoInteractions(redis);
            verifyNoInteractions(cache);
            // Rollback callbacks never invoke afterCommit; outbox rollback is covered in real-DB IT.
            TransactionSynchronizationManager.getSynchronizations().forEach(sync -> sync.afterCompletion(1));
            verifyNoInteractions(cache);
        } finally {
            TransactionSynchronizationManager.clear();
        }
    }
}
