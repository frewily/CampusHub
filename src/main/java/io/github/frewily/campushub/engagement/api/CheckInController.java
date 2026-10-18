package io.github.frewily.campushub.engagement.api;

import io.github.frewily.campushub.dto.Result;
import io.github.frewily.campushub.engagement.application.CheckInService;
import io.github.frewily.campushub.utils.UserHolder;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/user")
public class CheckInController {
    private final CheckInService checkInService;

    public CheckInController(CheckInService checkInService) {
        this.checkInService = checkInService;
    }

    @PostMapping("/sign")
    @PreAuthorize("@resourceAuthorization.canParticipateAsUser()")
    public Result sign() {
        checkInService.sign(UserHolder.getUser().getId());
        return Result.ok();
    }

    @GetMapping("/sign/count")
    @PreAuthorize("@resourceAuthorization.canParticipateAsUser()")
    public Result signCount() {
        return Result.ok(checkInService.signCount(UserHolder.getUser().getId()));
    }
}
