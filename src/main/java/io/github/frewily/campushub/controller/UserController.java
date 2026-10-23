package io.github.frewily.campushub.controller;


import io.github.frewily.campushub.dto.LoginFormDTO;
import io.github.frewily.campushub.dto.Result;
import io.github.frewily.campushub.service.IUserService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

import javax.annotation.Resource;
import javax.validation.Valid;
import javax.validation.constraints.NotBlank;
import javax.validation.constraints.Pattern;

import static io.github.frewily.campushub.utils.RegexPatterns.PHONE_REGEX;

@Slf4j
@RestController
@RequestMapping("/user")
@Validated
public class UserController {

    @Resource
    private IUserService userService;

    /**
     * 发送手机验证码
     */
    @PostMapping("code")
    public Result sendCode(
            @NotBlank(message = "手机号不能为空")
            @Pattern(regexp = PHONE_REGEX, message = "手机号格式错误")
            @RequestParam("phone") String phone) {
        return userService.sendCode(phone);
    }

    /**
     * 登录功能
     * @param loginForm 登录表单数据对象，包含：
     *                  - phone: 手机号（必填）
     *                  - code: 验证码（用于验证码登录）
     *                  - password: 密码（预留字段，当前未使用）
     *                  通过 @RequestBody 注解，Spring 会将请求体中的 JSON 数据自动转换为此对象
     */
    @PostMapping("/login")
    public Result login(@Valid @RequestBody LoginFormDTO loginForm) {
        return userService.login(loginForm);
    }

    /**
     * 登出功能
     * @return 无
     */
    @PostMapping("/logout")
    public Result logout(@RequestHeader("authorization") String token) {
        return userService.logout(token);
    }

}
