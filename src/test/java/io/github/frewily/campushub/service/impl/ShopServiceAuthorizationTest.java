package io.github.frewily.campushub.service.impl;

import io.github.frewily.campushub.entity.Shop;
import io.github.frewily.campushub.mapper.ShopMapper;
import io.github.frewily.campushub.service.ShopCacheInvalidationService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.test.util.ReflectionTestUtils;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ShopServiceAuthorizationTest {

    @Mock
    private ShopMapper shopMapper;
    @Mock
    private StringRedisTemplate stringRedisTemplate;
    @Mock
    private ShopCacheInvalidationService invalidations;

    @Test
    void genericUpdateMustPreserveExistingMerchantOwnership() {
        ShopServiceImpl shopService = new ShopServiceImpl();
        ReflectionTestUtils.setField(shopService, "baseMapper", shopMapper);
        ReflectionTestUtils.setField(shopService, "stringRedisTemplate", stringRedisTemplate);
        ReflectionTestUtils.setField(shopService, "shopCacheInvalidationService", invalidations);
        when(shopMapper.selectById(1L)).thenReturn(new Shop().setId(1L).setMerchantId(10L));
        when(shopMapper.updateById(org.mockito.ArgumentMatchers.any(Shop.class))).thenReturn(1);

        shopService.updateShop(new Shop().setId(1L).setName("updated").setMerchantId(99L));

        ArgumentCaptor<Shop> captor = ArgumentCaptor.forClass(Shop.class);
        verify(shopMapper).updateById(captor.capture());
        assertEquals(10L, captor.getValue().getMerchantId());
        verify(invalidations).enqueue(1L);
        org.mockito.Mockito.verifyNoInteractions(stringRedisTemplate);
    }

    @Test
    void failedUpdateDoesNotEnqueueOrInvalidateCache() {
        ShopServiceImpl service = new ShopServiceImpl();
        ReflectionTestUtils.setField(service, "baseMapper", shopMapper);
        ReflectionTestUtils.setField(service, "shopCacheInvalidationService", invalidations);
        when(shopMapper.selectById(1L)).thenReturn(new Shop().setId(1L));
        when(shopMapper.updateById(org.mockito.ArgumentMatchers.any(Shop.class))).thenReturn(0);
        org.junit.jupiter.api.Assertions.assertThrows(io.github.frewily.campushub.exception.BusinessException.class,
                () -> service.updateShop(new Shop().setId(1L).setName("new")));
        org.mockito.Mockito.verifyNoInteractions(invalidations, stringRedisTemplate);
    }

    @Test
    void failedCreationDoesNotReportSuccessOrEnqueueCacheInvalidation() {
        ShopServiceImpl service = new ShopServiceImpl();
        ReflectionTestUtils.setField(service, "baseMapper", shopMapper);
        ReflectionTestUtils.setField(service, "shopCacheInvalidationService", invalidations);
        org.junit.jupiter.api.Assertions.assertThrows(io.github.frewily.campushub.exception.BusinessException.class,
                () -> service.createShop(new Shop().setName("new")));
        org.mockito.Mockito.verifyNoInteractions(invalidations, stringRedisTemplate);
    }
}
