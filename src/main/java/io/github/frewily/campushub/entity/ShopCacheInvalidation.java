package io.github.frewily.campushub.entity;

import lombok.Data;
import lombok.experimental.Accessors;

@Data
@Accessors(chain = true)
public class ShopCacheInvalidation {
    private Long shopId;
    private String generation;
    private Integer attempts;
    private String lastError;
}
