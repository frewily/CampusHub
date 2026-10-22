package io.github.frewily.campushub.promotion.application;

import io.github.frewily.campushub.entity.Voucher;
import java.util.List;

public interface VoucherQueryRepository {
    List<Voucher> findByShopId(Long shopId);
}
