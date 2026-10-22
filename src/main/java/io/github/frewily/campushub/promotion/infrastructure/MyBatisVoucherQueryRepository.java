package io.github.frewily.campushub.promotion.infrastructure;

import io.github.frewily.campushub.entity.Voucher;
import io.github.frewily.campushub.mapper.VoucherMapper;
import io.github.frewily.campushub.promotion.application.VoucherQueryRepository;
import org.springframework.stereotype.Component;
import java.util.List;

@Component
public class MyBatisVoucherQueryRepository implements VoucherQueryRepository {
    private final VoucherMapper mapper;

    public MyBatisVoucherQueryRepository(VoucherMapper mapper) { this.mapper = mapper; }

    @Override
    public List<Voucher> findByShopId(Long shopId) { return mapper.queryVoucherOfShop(shopId); }
}
