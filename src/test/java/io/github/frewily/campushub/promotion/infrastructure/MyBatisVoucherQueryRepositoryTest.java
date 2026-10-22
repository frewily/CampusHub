package io.github.frewily.campushub.promotion.infrastructure;

import io.github.frewily.campushub.entity.Voucher;
import io.github.frewily.campushub.mapper.VoucherMapper;
import org.junit.jupiter.api.Test;

import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.*;

class MyBatisVoucherQueryRepositoryTest {
    @Test
    void callsLegacyMapperWithExactIdAndReturnsItsRowsUnchanged() {
        VoucherMapper mapper = mock(VoucherMapper.class);
        List<Voucher> rows = Collections.singletonList(new Voucher().setId(9007199254740993L));
        when(mapper.queryVoucherOfShop(Long.MAX_VALUE)).thenReturn(rows);
        MyBatisVoucherQueryRepository repository = new MyBatisVoucherQueryRepository(mapper);

        assertSame(rows, repository.findByShopId(Long.MAX_VALUE));

        verify(mapper).queryVoucherOfShop(Long.MAX_VALUE);
        verifyNoMoreInteractions(mapper);
    }

    @Test
    void propagatesMapperFailureUnchanged() {
        VoucherMapper mapper = mock(VoucherMapper.class);
        RuntimeException failure = new IllegalStateException("synthetic mapper failure");
        when(mapper.queryVoucherOfShop(3L)).thenThrow(failure);
        MyBatisVoucherQueryRepository repository = new MyBatisVoucherQueryRepository(mapper);

        assertSame(failure, assertThrows(RuntimeException.class, () -> repository.findByShopId(3L)));

        verify(mapper).queryVoucherOfShop(3L);
        verifyNoMoreInteractions(mapper);
    }
}
