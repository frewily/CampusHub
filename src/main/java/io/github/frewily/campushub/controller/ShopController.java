package io.github.frewily.campushub.controller;


import cn.hutool.core.util.StrUtil;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import io.github.frewily.campushub.dto.Result;
import io.github.frewily.campushub.dto.ApiModelMapper;
import io.github.frewily.campushub.dto.request.ShopCreateRequest;
import io.github.frewily.campushub.dto.request.ShopUpdateRequest;
import io.github.frewily.campushub.entity.Shop;
import io.github.frewily.campushub.service.IShopService;
import io.github.frewily.campushub.utils.SystemConstants;
import org.springframework.validation.annotation.Validated;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import javax.annotation.Resource;
import javax.validation.Valid;
import javax.validation.constraints.DecimalMax;
import javax.validation.constraints.DecimalMin;
import javax.validation.constraints.Min;
import javax.validation.constraints.NotNull;
import javax.validation.constraints.Positive;

@RestController
@RequestMapping("/shop")
@Validated
public class ShopController {

    @Resource
    public IShopService shopService;

    /**
     * 根据id查询商铺信息
     * @param id 商铺id
     * @return 商铺详情数据
     */
    @GetMapping("/{id}")
    public Result queryShopById(@Positive(message = "门店ID必须为正数") @PathVariable("id") Long id) {
        return shopService.queryById(id);
    }

    /**
     * 新增商铺信息
     * @param shop 商铺数据
     * @return 商铺id
     */
    @PostMapping
    @PreAuthorize("@resourceAuthorization.canCreateShop(#shop.merchantId)")
    public Result saveShop(@Valid @NotNull @RequestBody ShopCreateRequest shop) {
        return shopService.createShop(ApiModelMapper.toShop(shop));
    }

    /**
     * 更新商铺信息
     * @param shop 商铺数据
     * @return 无
     */
    @PutMapping
    @PreAuthorize("@resourceAuthorization.canManageShop(#shop.id)")
    public Result updateShop(@Valid @NotNull @RequestBody ShopUpdateRequest shop) {
        return shopService.updateShop(ApiModelMapper.toShop(shop));
    }

    /**
     * 根据商铺类型分页查询商铺信息
     * @param typeId 商铺类型
     * @param current 页码
     * @return 商铺列表
     */
    @GetMapping("/of/type")
    public Result queryShopByType(
            @Positive(message = "门店类型ID必须为正数") @RequestParam("typeId") Integer typeId,
            @Min(value = 1, message = "页码不能小于1")
            @RequestParam(value = "current", defaultValue = "1") Integer current,
            @DecimalMin(value = "-180", message = "经度不能小于-180")
            @DecimalMax(value = "180", message = "经度不能大于180")
            @RequestParam(required = false) Double x,
            @DecimalMin(value = "-90", message = "纬度不能小于-90")
            @DecimalMax(value = "90", message = "纬度不能大于90")
            @RequestParam(required = false) Double y
    ) {
        return shopService.queryShopByType(typeId, current, x, y);
    }

    /**
     * 根据商铺名称关键字分页查询商铺信息
     * @param name 商铺名称关键字
     * @param current 页码
     * @return 商铺列表
     */
    @GetMapping("/of/name")
    public Result queryShopByName(
            @RequestParam(value = "name", required = false) String name,
            @Min(value = 1, message = "页码不能小于1")
            @RequestParam(value = "current", defaultValue = "1") Integer current
    ) {
        Page<Shop> page = shopService.query()
                .like(StrUtil.isNotBlank(name), "name", name)
                .page(new Page<>(current, SystemConstants.MAX_PAGE_SIZE));
        return Result.ok(page.getRecords());
    }
}
