package io.github.frewily.campushub.merchant.application;

import io.github.frewily.campushub.entity.ShopType;

import java.util.List;

/** Orchestrates the existing cache-first category read; persistence and JSON remain in adapters. */
public class ShopTypeQueryService {
    private final ShopTypeRepository repository;
    private final ShopTypeCache cache;

    public ShopTypeQueryService(ShopTypeRepository repository, ShopTypeCache cache) {
        this.repository = repository;
        this.cache = cache;
    }

    public List<ShopType> queryList() {
        ShopTypeCache.Lookup cached = cache.read();
        if (cached.isHit()) {
            return cached.getItems();
        }
        List<ShopType> types = repository.findAllOrderBySort();
        cache.write(types);
        return types;
    }
}
