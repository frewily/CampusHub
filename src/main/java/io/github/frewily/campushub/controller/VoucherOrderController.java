package io.github.frewily.campushub.controller;


import io.github.frewily.campushub.dto.Result;
import io.github.frewily.campushub.service.IVoucherOrderService;
import io.github.frewily.campushub.service.OrderLifecycleService;
import org.springframework.validation.annotation.Validated;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;

import javax.annotation.Resource;
import javax.validation.constraints.Positive;

@RestController
@RequestMapping("/voucher-order")
@Validated
public class VoucherOrderController {

    @Resource
    private IVoucherOrderService voucherOrderService;

    @Resource
    private OrderLifecycleService orderLifecycleService;

    @PostMapping("seckill/{id}")
    @PreAuthorize("@resourceAuthorization.canParticipateAsUser()")
    public Result seckillVoucher(
            @Positive(message = "活动ID必须为正数") @PathVariable("id") Long voucherId) {
        return voucherOrderService.seckillVoucher(voucherId);
    }

    @GetMapping("/{id}")
    @PreAuthorize("@resourceAuthorization.canParticipateAsUser()")
    public Result queryMine(@Positive @PathVariable("id") Long id,
                            @Positive @RequestParam("voucherId") Long voucherId) {
        return Result.ok(orderLifecycleService.queryMine(id, voucherId));
    }

    @PostMapping("/{id}/cancel")
    @PreAuthorize("@resourceAuthorization.canParticipateAsUser()")
    public Result cancelMine(@Positive @PathVariable("id") Long id,
                             @Positive @RequestParam("voucherId") Long voucherId) {
        return Result.ok(orderLifecycleService.cancelMine(id, voucherId));
    }
}
