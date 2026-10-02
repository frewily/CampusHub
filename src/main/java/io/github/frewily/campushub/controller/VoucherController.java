package io.github.frewily.campushub.controller;


import io.github.frewily.campushub.dto.Result;
import io.github.frewily.campushub.dto.ApiModelMapper;
import io.github.frewily.campushub.dto.request.VoucherCreateRequest;
import io.github.frewily.campushub.dto.request.FlashSaleCreateRequest;
import io.github.frewily.campushub.entity.Voucher;
import io.github.frewily.campushub.service.IVoucherService;
import org.springframework.validation.annotation.Validated;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import javax.annotation.Resource;
import javax.validation.Valid;
import javax.validation.constraints.NotNull;
import javax.validation.constraints.Positive;

@RestController
@RequestMapping("/voucher")
@Validated
public class VoucherController {

    @Resource
    private IVoucherService voucherService;

    /**
     * 新增普通券
     * @param voucher 优惠券信息
     * @return 优惠券id
     */
    @PostMapping
    @PreAuthorize("@resourceAuthorization.canManagePromotion(#voucher.shopId)")
    public Result addVoucher(@Valid @NotNull @RequestBody VoucherCreateRequest voucher) {
        return voucherService.addVoucher(ApiModelMapper.toVoucher(voucher));
    }

    /**
     * 新增秒杀券
     * @param voucher 优惠券信息，包含秒杀信息
     * @return 优惠券id
     */
    @PostMapping("seckill")
    @PreAuthorize("@resourceAuthorization.canManagePromotion(#voucher.shopId)")
    public Result addSeckillVoucher(@Valid @NotNull @RequestBody FlashSaleCreateRequest voucher) {
        Voucher entity = ApiModelMapper.toFlashSale(voucher);
        voucherService.addSeckillVoucher(entity);
        return Result.ok(entity.getId());
    }

    /**
     * 查询店铺的优惠券列表
     * @param shopId 店铺id
     * @return 优惠券列表
     */
    @GetMapping("/list/{shopId}")
    public Result queryVoucherOfShop(
            @Positive(message = "门店ID必须为正数") @PathVariable("shopId") Long shopId) {
       return voucherService.queryVoucherOfShop(shopId);
    }
}
