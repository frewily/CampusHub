package io.github.frewily.campushub.dto.request;

import lombok.Data;
import javax.validation.constraints.*;

/** Public search filters; no SQL column/expression or entity write fields are bindable. */
@Data
public class ShopSearchRequest {
    @Size(max = 80) private String keyword;
    @Positive private Long typeId;
    @PositiveOrZero private Long minPrice;
    @PositiveOrZero private Long maxPrice;
    @Min(0) @Max(50) private Integer minScore;
    private Double x;
    private Double y;
    @Min(1) @Max(50000) private Integer radiusMeters;
    @NotNull @Pattern(regexp = "id|price_asc|price_desc|score_desc|distance")
    private String sort = "id";
    @NotNull @Min(1) @Max(500) private Integer page = 1;
    @NotNull @Min(1) @Max(50) private Integer size = 10;

    @AssertTrue(message = "价格下限不能大于上限")
    public boolean isPriceRangeValid() {
        return minPrice == null || maxPrice == null || minPrice <= maxPrice;
    }
    @AssertTrue(message = "距离搜索需要完整、有限且范围正确的经纬度")
    public boolean isCoordinatesValid() {
        if ((x == null) != (y == null)) return false;
        if (x == null) return radiusMeters == null && !"distance".equals(sort);
        return Double.isFinite(x) && Double.isFinite(y) && x >= -180 && x <= 180 && y >= -90 && y <= 90;
    }
}
