package io.github.frewily.campushub.identity.api;

import io.github.frewily.campushub.dto.Result;
import io.github.frewily.campushub.identity.application.SessionLogoutService;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/user")
public class LogoutController {
    private final SessionLogoutService logoutService;

    public LogoutController(SessionLogoutService logoutService) { this.logoutService = logoutService; }

    @PostMapping("/logout")
    public Result logout(@RequestHeader("authorization") String token) {
        logoutService.logout(token);
        return Result.ok();
    }
}
