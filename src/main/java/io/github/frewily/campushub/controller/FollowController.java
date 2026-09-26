package io.github.frewily.campushub.controller;


import io.github.frewily.campushub.dto.Result;
import io.github.frewily.campushub.service.IFollowService;
import org.springframework.validation.annotation.Validated;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import javax.annotation.Resource;
import javax.validation.constraints.NotNull;
import javax.validation.constraints.Positive;

@RestController
@RequestMapping("/follow")
@Validated
public class FollowController {

    @Resource
    private IFollowService followService;

    @PutMapping("/{id}/{isFollow}")
    @PreAuthorize("hasAnyRole('USER', 'MERCHANT', 'ADMIN')")
    public Result follow(
            @Positive(message = "用户ID必须为正数") @PathVariable("id") Long followUserId,
            @NotNull(message = "关注状态不能为空") @PathVariable Boolean isFollow) {
        return followService.follow(followUserId, isFollow);
    }

    @GetMapping("/or/not/{id}")
    public Result isFollow(@Positive(message = "用户ID必须为正数") @PathVariable("id") Long followUserId) {
        return followService.isFollow(followUserId);
    }

    @GetMapping("/common/{id}")
    public Result common(@Positive(message = "用户ID必须为正数") @PathVariable Long id) {
        return followService.common(id);
    }

}
