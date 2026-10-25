package io.github.frewily.campushub.identity.api;

import io.github.frewily.campushub.dto.Result;
import io.github.frewily.campushub.identity.application.LoginCodeService;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

import javax.validation.constraints.NotBlank;
import javax.validation.constraints.Pattern;

import static io.github.frewily.campushub.utils.RegexPatterns.PHONE_REGEX;

@RestController
@RequestMapping("/user")
@Validated
public class LoginCodeController {
    private final LoginCodeService codes;

    public LoginCodeController(LoginCodeService codes) { this.codes = codes; }

    @PostMapping("code")
    public Result sendCode(
            @NotBlank(message = "手机号不能为空")
            @Pattern(regexp = PHONE_REGEX, message = "手机号格式错误")
            @RequestParam("phone") String phone) {
        codes.sendCode(phone);
        return Result.ok("发送验证码成功");
    }
}
