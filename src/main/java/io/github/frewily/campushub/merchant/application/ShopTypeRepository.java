package io.github.frewily.campushub.merchant.application;

import io.github.frewily.campushub.entity.ShopType;

import java.util.List;

public interface ShopTypeRepository {
    /** Ascending sort only; equal sort values have no additional ordering guarantee. */
    List<ShopType> findAllOrderBySort();
}
