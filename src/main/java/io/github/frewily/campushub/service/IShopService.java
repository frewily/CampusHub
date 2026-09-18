package io.github.frewily.campushub.service;

import io.github.frewily.campushub.dto.Result;
import io.github.frewily.campushub.entity.Shop;
import com.baomidou.mybatisplus.extension.service.IService;

public interface IShopService extends IService<Shop> {

    Result queryById(Long id);

    Result updateShop(Shop shop);

    Result queryShopByType(Integer typeId, Integer current, Double x, Double y);
}
