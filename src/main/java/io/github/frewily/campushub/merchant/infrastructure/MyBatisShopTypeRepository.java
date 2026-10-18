package io.github.frewily.campushub.merchant.infrastructure;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import io.github.frewily.campushub.entity.ShopType;
import io.github.frewily.campushub.mapper.ShopTypeMapper;
import io.github.frewily.campushub.merchant.application.ShopTypeRepository;
import org.springframework.stereotype.Component;

import java.util.List;

@Component
public class MyBatisShopTypeRepository implements ShopTypeRepository {
    private final ShopTypeMapper mapper;

    public MyBatisShopTypeRepository(ShopTypeMapper mapper) {
        this.mapper = mapper;
    }

    @Override
    public List<ShopType> findAllOrderBySort() {
        return mapper.selectList(new QueryWrapper<ShopType>().orderByAsc("sort"));
    }
}
