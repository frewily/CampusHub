package io.github.frewily.campushub.service;

import com.baomidou.mybatisplus.extension.service.IService;
import io.github.frewily.campushub.dto.LoginFormDTO;
import io.github.frewily.campushub.dto.Result;
import io.github.frewily.campushub.entity.User;

public interface IUserService extends IService<User> {

    /**
     * 发送验证码
     * @param phone 手机号
     * @return 验证码发送结果
     */
    Result sendCode(String phone);

    /**
     * 登录功能
     * @param loginForm 登录参数，包含手机号、验证码；或者手机号、密码
     */
    Result login(LoginFormDTO loginForm);

}
