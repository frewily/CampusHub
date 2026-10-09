package io.github.frewily.campushub.dto;

import io.github.frewily.campushub.dto.request.ShopSearchRequest;
import lombok.Getter;

/** Validated immutable query projection. Not a request binding target. */
@Getter
public final class ShopSearchCriteria {
    private final String likeKeyword, sort;
    private final Long typeId, minPrice, maxPrice;
    private final Integer minScore, radiusMeters, size, offset;
    private final Double x, y;

    public ShopSearchCriteria(ShopSearchRequest request) {
        String keyword = request.getKeyword() == null ? null : request.getKeyword().trim();
        likeKeyword = keyword == null || keyword.isEmpty() ? null
                : "%" + keyword.replace("!", "!!").replace("%", "!%").replace("_", "!_") + "%";
        sort = request.getSort(); typeId = request.getTypeId(); minPrice = request.getMinPrice();
        maxPrice = request.getMaxPrice(); minScore = request.getMinScore(); radiusMeters = request.getRadiusMeters();
        size = request.getSize(); offset = Math.multiplyExact(request.getPage() - 1, size);
        x = request.getX(); y = request.getY();
    }
}
