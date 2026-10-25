package io.github.frewily.campushub.service;

import io.github.frewily.campushub.dto.LoginFormDTO;
import io.github.frewily.campushub.dto.Result;

public interface IUserService {

    /**
     * 登录功能
     * @param loginForm 登录参数，包含手机号、验证码；或者手机号、密码
     */
    Result login(LoginFormDTO loginForm);

}
