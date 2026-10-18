package io.github.frewily.campushub.merchant.application;

import io.github.frewily.campushub.entity.ShopType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class ShopTypeQueryServiceTest {
    private ShopTypeRepository repository;
    private ShopTypeCache cache;
    private ShopTypeQueryService service;

    @BeforeEach
    void setUp() {
        repository = mock(ShopTypeRepository.class);
        cache = mock(ShopTypeCache.class);
        service = new ShopTypeQueryService(repository, cache);
    }

    @Test
    void hitReturnsTheExistingOrderWithoutSqlOrCacheRefresh() {
        List<ShopType> rows = Arrays.asList(new ShopType().setId(8L).setSort(9), new ShopType().setId(3L).setSort(1));
        when(cache.read()).thenReturn(ShopTypeCache.Lookup.hit(rows));
        assertSame(rows, service.queryList());
        verify(cache).read();
        verifyNoMoreInteractions(cache);
        verifyNoInteractions(repository);
    }

    @Test
    void emptyHitDoesNotBecomeAMiss() {
        when(cache.read()).thenReturn(ShopTypeCache.Lookup.hit(Collections.emptyList()));
        assertTrue(service.queryList().isEmpty());
        verifyNoInteractions(repository);
        verify(cache, never()).write(any());
    }

    @Test
    void decodedNullHitDoesNotSilentlyIntroduceAFallback() {
        when(cache.read()).thenReturn(ShopTypeCache.Lookup.hit(null));
        assertNull(service.queryList());
        verifyNoInteractions(repository);
        verify(cache, never()).write(any());
    }

    @Test
    void missReadsTheOrderedRepositoryThenCachesTheSameRows() {
        List<ShopType> rows = Collections.singletonList(new ShopType().setId(2L).setName("synthetic"));
        when(cache.read()).thenReturn(ShopTypeCache.Lookup.miss());
        when(repository.findAllOrderBySort()).thenReturn(rows);
        assertSame(rows, service.queryList());
        InOrder order = inOrder(cache, repository);
        order.verify(cache).read();
        order.verify(repository).findAllOrderBySort();
        order.verify(cache).write(rows);
        order.verifyNoMoreInteractions();
    }

    @Test
    void emptyDatabaseResultIsStillCached() {
        when(cache.read()).thenReturn(ShopTypeCache.Lookup.miss());
        when(repository.findAllOrderBySort()).thenReturn(Collections.emptyList());
        assertTrue(service.queryList().isEmpty());
        verify(cache).write(Collections.emptyList());
    }

    @Test
    void readOrDecodeFailureIsNotHiddenBySqlFallback() {
        RuntimeException failure = new IllegalStateException("synthetic cache failure");
        when(cache.read()).thenThrow(failure);
        assertSame(failure, assertThrows(RuntimeException.class, service::queryList));
        verifyNoInteractions(repository);
        verify(cache, never()).write(any());
    }

    @Test
    void sqlFailureDoesNotWriteASuccessfulCacheEntry() {
        RuntimeException failure = new IllegalStateException("synthetic SQL failure");
        when(cache.read()).thenReturn(ShopTypeCache.Lookup.miss());
        when(repository.findAllOrderBySort()).thenThrow(failure);
        assertSame(failure, assertThrows(RuntimeException.class, service::queryList));
        verify(cache, never()).write(any());
    }

    @Test
    void cacheWriteFailureKeepsTheLegacyErrorInsteadOfReturningSuccess() {
        when(cache.read()).thenReturn(ShopTypeCache.Lookup.miss());
        List<ShopType> rows = Collections.emptyList();
        when(repository.findAllOrderBySort()).thenReturn(rows);
        RuntimeException failure = new IllegalStateException("synthetic write failure");
        doThrow(failure).when(cache).write(rows);
        assertSame(failure, assertThrows(RuntimeException.class, service::queryList));
    }
}
