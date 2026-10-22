package io.github.frewily.campushub.social.api;

import io.github.frewily.campushub.dto.Result;
import io.github.frewily.campushub.social.application.FollowCommandService;
import io.github.frewily.campushub.utils.UserHolder;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;
import javax.validation.constraints.NotNull;
import javax.validation.constraints.Positive;

@RestController
@RequestMapping("/follow")
@Validated
public class FollowController {
    private final FollowCommandService followService;

    public FollowController(FollowCommandService followService) { this.followService = followService; }

    @PutMapping("/{id}/{isFollow}")
    @PreAuthorize("hasAnyRole('USER', 'MERCHANT', 'ADMIN')")
    public Result follow(
            @Positive(message = "用户ID必须为正数") @PathVariable("id") Long followUserId,
            @NotNull(message = "关注状态不能为空") @PathVariable Boolean isFollow) {
        followService.follow(UserHolder.getUser().getId(), followUserId, isFollow);
        return Result.ok();
    }
}
