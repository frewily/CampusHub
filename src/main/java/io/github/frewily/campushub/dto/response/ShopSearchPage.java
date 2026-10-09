package io.github.frewily.campushub.dto.response;

import lombok.AllArgsConstructor;
import lombok.Getter;
import java.util.List;

@Getter
@AllArgsConstructor
public class ShopSearchPage {
    private final List<ShopSearchItem> items;
    private final long total;
    private final int page;
    private final int size;
    private final String sort;
    private final boolean hasNext;
}
