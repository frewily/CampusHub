package io.github.frewily.campushub.mapper;

import io.github.frewily.campushub.dto.ShopSearchCriteria;
import io.github.frewily.campushub.dto.response.ShopSearchItem;
import org.apache.ibatis.annotations.Param;
import java.util.List;

public interface ShopSearchMapper {
    long count(@Param("q") ShopSearchCriteria criteria);
    List<ShopSearchItem> search(@Param("q") ShopSearchCriteria criteria);
}
