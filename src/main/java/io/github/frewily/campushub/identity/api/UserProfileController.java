package io.github.frewily.campushub.identity.api;

import io.github.frewily.campushub.dto.Result;
import io.github.frewily.campushub.identity.application.UserProfileQueryService;
import io.github.frewily.campushub.utils.UserHolder;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;
import javax.validation.constraints.Positive;

@RestController
@RequestMapping("/user")
@Validated
public class UserProfileController {
    private final UserProfileQueryService service;

    public UserProfileController(UserProfileQueryService service) { this.service = service; }

    @GetMapping("/me")
    public Result me() { return Result.ok(UserHolder.getUser()); }

    @GetMapping("/info/{id}")
    public Result info(@Positive(message = "用户ID必须为正数") @PathVariable("id") Long userId) {
        return Result.ok(service.info(userId));
    }

    @GetMapping("/{id}")
    public Result queryUserById(@Positive(message = "用户ID必须为正数") @PathVariable("id") Long userId) {
        return Result.ok(service.queryUserById(userId));
    }
}
