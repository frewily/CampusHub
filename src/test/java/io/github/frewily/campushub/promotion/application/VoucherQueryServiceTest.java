package io.github.frewily.campushub.promotion.application;

import io.github.frewily.campushub.entity.Voucher;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.*;

class VoucherQueryServiceTest {
    @Test
    void delegatesShopIdAndReturnsTheExactListWithoutReordering() {
        VoucherQueryRepository repository = mock(VoucherQueryRepository.class);
        VoucherQueryService service = new VoucherQueryService(repository);
        List<Voucher> rows = Arrays.asList(new Voucher().setId(9L), new Voucher().setId(2L));
        when(repository.findByShopId(Long.MAX_VALUE)).thenReturn(rows);

        assertSame(rows, service.queryVoucherOfShop(Long.MAX_VALUE));

        verify(repository).findByShopId(Long.MAX_VALUE);
        verifyNoMoreInteractions(repository);
    }

    @Test
    void preservesNullAndDoesNotValidateOrNormalizeTheInput() {
        VoucherQueryRepository repository = mock(VoucherQueryRepository.class);
        VoucherQueryService service = new VoucherQueryService(repository);
        when(repository.findByShopId(null)).thenReturn(null);

        assertNull(service.queryVoucherOfShop(null));

        verify(repository).findByShopId(null);
        verifyNoMoreInteractions(repository);
    }

    @Test
    void propagatesRepositoryFailureUnchanged() {
        VoucherQueryRepository repository = mock(VoucherQueryRepository.class);
        VoucherQueryService service = new VoucherQueryService(repository);
        RuntimeException failure = new IllegalStateException("synthetic query failure");
        when(repository.findByShopId(7L)).thenThrow(failure);

        assertSame(failure, assertThrows(RuntimeException.class, () -> service.queryVoucherOfShop(7L)));

        verify(repository).findByShopId(7L);
        verifyNoMoreInteractions(repository);
    }
}
