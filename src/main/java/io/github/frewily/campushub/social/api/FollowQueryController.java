package io.github.frewily.campushub.social.api;

import io.github.frewily.campushub.dto.Result;
import io.github.frewily.campushub.social.application.FollowQueryService;
import io.github.frewily.campushub.utils.UserHolder;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import javax.validation.constraints.Positive;

@RestController
@RequestMapping("/follow")
@Validated
public class FollowQueryController {
    private final FollowQueryService followService;

    public FollowQueryController(FollowQueryService followService) {
        this.followService = followService;
    }

    @GetMapping("/or/not/{id}")
    public Result isFollow(@Positive(message = "用户ID必须为正数") @PathVariable("id") Long followUserId) {
        return Result.ok(followService.isFollow(UserHolder.getUser().getId(), followUserId));
    }

    @GetMapping("/common/{id}")
    public Result common(@Positive(message = "用户ID必须为正数") @PathVariable("id") Long id) {
        return Result.ok(followService.common(UserHolder.getUser().getId(), id));
    }
}
