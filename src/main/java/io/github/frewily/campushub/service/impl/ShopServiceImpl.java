package io.github.frewily.campushub.service.impl;

import cn.hutool.core.util.StrUtil;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import io.github.frewily.campushub.dto.Result;
import io.github.frewily.campushub.entity.Shop;
import io.github.frewily.campushub.exception.BusinessException;
import io.github.frewily.campushub.exception.ErrorCode;
import io.github.frewily.campushub.mapper.ShopMapper;
import io.github.frewily.campushub.service.IShopService;
import io.github.frewily.campushub.service.ShopCacheInvalidationService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import io.github.frewily.campushub.utils.CacheClient;
import org.springframework.data.geo.Distance;
import org.springframework.data.geo.GeoResult;
import org.springframework.data.geo.GeoResults;
import org.springframework.data.redis.connection.RedisGeoCommands;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.domain.geo.GeoReference;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.security.access.prepost.PreAuthorize;

import javax.annotation.Resource;

import java.util.*;

import static io.github.frewily.campushub.utils.RedisConstants.*;

@Service
public class ShopServiceImpl extends ServiceImpl<ShopMapper, Shop> implements IShopService {

    @Resource
    StringRedisTemplate stringRedisTemplate;

    @Resource
    CacheClient cacheClient;

    @Resource
    ShopCacheInvalidationService shopCacheInvalidationService;

    @Override
    public Result queryById(Long id) {
        Shop shop = cacheClient.queryShop(id, Shop.class, this::getById);
        if (shop == null) {
            throw new BusinessException(ErrorCode.NOT_FOUND, "门店不存在");
        }
        return Result.ok(shop);
    }

    @Override
    @Transactional
    @PreAuthorize("@resourceAuthorization.canCreateShop(#shop.merchantId)")
    public Result createShop(Shop shop) {
        if (!save(shop)) throw new BusinessException(ErrorCode.OPERATION_FAILED);
        shopCacheInvalidationService.enqueue(shop.getId());
        return Result.ok(shop.getId());
    }

    @Override
    @Transactional
    @PreAuthorize("@resourceAuthorization.canManageShop(#shop.id)")
    public Result updateShop(Shop shop) {
        Long id = shop.getId();
        if (id == null) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED, "门店ID不能为空");
        }
        Shop existing = getById(id);
        if (existing == null) {
            throw new BusinessException(ErrorCode.NOT_FOUND, "门店不存在");
        }
        // 通用更新接口不能转移门店归属；认领或转移需要独立的管理员用例。
        shop.setMerchantId(existing.getMerchantId());
        if (!updateById(shop)) throw new BusinessException(ErrorCode.CONFLICT);
        shopCacheInvalidationService.enqueue(id);
        return Result.ok();
    }

    @Override
    public Result queryShopByType(Integer typeId, Integer current, Double x, Double y) {
        if (x == null || y == null) {
            Page<Shop> page = query()
                    .eq("type_id", typeId)
                    .page(new Page<>(current, DEFAULT_BATCH_SIZE));
            return Result.ok(page.getRecords());
        }
        int from = (current - 1) * DEFAULT_BATCH_SIZE;
        int end = current * DEFAULT_BATCH_SIZE;
        String key = SHOP_GEO_KEY + typeId;
        GeoResults<RedisGeoCommands.GeoLocation<String>> results = stringRedisTemplate.opsForGeo()
                .search(key,
                        GeoReference.fromCoordinate(x, y),
                        new Distance(5000),
                        RedisGeoCommands.GeoSearchCommandArgs.newGeoSearchArgs()
                                .includeCoordinates()
                                .includeDistance().
                                limit(end)
                );
        if (results == null) {
            return Result.ok(Collections.emptyList());
        }
        List<GeoResult<RedisGeoCommands.GeoLocation<String>>> list = results.getContent();
        List<Long> ids = new ArrayList<>(list.size());
        Map<String, Distance> distanceMap = new HashMap<>(list.size());
        list.stream().skip(from).forEach(result -> {
            String shopIdStr = result.getContent().getName();
            ids.add(Long.valueOf(shopIdStr));
            Distance distance = result.getDistance();
            distanceMap.put(shopIdStr, distance);
        });
        if (ids.isEmpty()) {
            return Result.ok(Collections.emptyList());
        }
        String idStr = StrUtil.join(",", ids);
        List<Shop> shops = query().in("id", ids).last("ORDER BY FIELD(id," + idStr + ")").list();
        for (Shop shop : shops) {
            shop.setDistance(distanceMap.get(shop.getId().toString()).getValue());
        }
        return Result.ok(shops);
    }
}
