package io.github.frewily.campushub.controller;


import io.github.frewily.campushub.dto.LoginFormDTO;
import io.github.frewily.campushub.dto.Result;
import io.github.frewily.campushub.service.IUserService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

import javax.annotation.Resource;
import javax.validation.Valid;

@Slf4j
@RestController
@RequestMapping("/user")
@Validated
public class UserController {

    @Resource
    private IUserService userService;

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

}
