package io.github.frewily.campushub.promotion.api;

import io.github.frewily.campushub.dto.Result;
import io.github.frewily.campushub.promotion.application.VoucherQueryService;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;
import javax.validation.constraints.Positive;

@RestController
@RequestMapping("/voucher")
@Validated
public class VoucherQueryController {
    private final VoucherQueryService service;

    public VoucherQueryController(VoucherQueryService service) { this.service = service; }

    @GetMapping("/list/{shopId}")
    public Result queryVoucherOfShop(
            @Positive(message = "门店ID必须为正数") @PathVariable("shopId") Long shopId) {
        return Result.ok(service.queryVoucherOfShop(shopId));
    }
}
