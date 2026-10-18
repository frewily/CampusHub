package io.github.frewily.campushub.merchant.api;

import io.github.frewily.campushub.dto.Result;
import io.github.frewily.campushub.merchant.application.ShopTypeQueryService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/shop-type")
public class ShopTypeController {
    private final ShopTypeQueryService typeService;

    public ShopTypeController(ShopTypeQueryService typeService) {
        this.typeService = typeService;
    }

    @GetMapping("list")
    public Result queryTypeList() {
        return Result.ok(typeService.queryList());
    }
}
