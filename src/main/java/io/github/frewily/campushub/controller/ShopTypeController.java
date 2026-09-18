package io.github.frewily.campushub.controller;


import io.github.frewily.campushub.dto.Result;
import io.github.frewily.campushub.entity.ShopType;
import io.github.frewily.campushub.service.IShopTypeService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import javax.annotation.Resource;
import java.util.List;

@RestController
@RequestMapping("/shop-type")
public class ShopTypeController {
    @Resource
    private IShopTypeService typeService;

    @GetMapping("list")
    public Result queryTypeList() {
        return Result.ok(typeService.queryList());
    }
}
