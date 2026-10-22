package io.github.frewily.campushub.promotion.application;

import io.github.frewily.campushub.entity.Voucher;
import java.util.List;

/** Reads the existing catalog without adding time, stock, ordering or existence rules. */
public class VoucherQueryService {
    private final VoucherQueryRepository repository;

    public VoucherQueryService(VoucherQueryRepository repository) { this.repository = repository; }

    public List<Voucher> queryVoucherOfShop(Long shopId) { return repository.findByShopId(shopId); }
}
