package io.github.frewily.campushub.service.impl;

import io.github.frewily.campushub.dto.LoginFormDTO;
import io.github.frewily.campushub.dto.Result;
import io.github.frewily.campushub.identity.application.LoginAccount;
import io.github.frewily.campushub.identity.application.LoginAccountRepository;
import io.github.frewily.campushub.identity.application.LoginAttemptStore;
import io.github.frewily.campushub.identity.application.LoginSessionIssuer;
import io.github.frewily.campushub.exception.BusinessException;
import io.github.frewily.campushub.exception.ErrorCode;
import io.github.frewily.campushub.service.IUserService;
import io.github.frewily.campushub.utils.RegexUtils;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import javax.annotation.Resource;
import static io.github.frewily.campushub.utils.RedisConstants.LOGIN_FAILURE_LIMIT;

@Service
public class UserServiceImpl implements IUserService {

    @Resource
    private LoginAttemptStore attempts;

    @Resource
    private LoginAccountRepository accounts;

    @Resource
    private LoginSessionIssuer sessions;

    @Override
    @Transactional
    public Result login(LoginFormDTO loginForm) {
        String phone = loginForm.getPhone();
        if (phone == null || RegexUtils.isPhoneInvalid(phone)) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED, "手机号格式错误");
        }
        String failureCount = attempts.findFailureCount(phone);
        if (failureCount != null && Long.parseLong(failureCount) >= LOGIN_FAILURE_LIMIT) {
            throw loginRateLimited();
        }
        String cacheCode = loginForm.getCode();
        if (cacheCode == null) {
            throw recordLoginFailure(phone);
        }
        Long consumed = attempts.consumeCode(phone, cacheCode);
        if (consumed == null) {
            throw new IllegalStateException("验证码校验未返回结果");
        }
        if (consumed != 1L) {
            throw recordLoginFailure(phone);
        }
        LoginAccount user = accounts.findByPhone(phone);
        if (user == null) {
            user = accounts.createWithPhone(phone);
        }
        if (!"ACTIVE".equals(user.getStatus())) {
            throw new BusinessException(ErrorCode.AUTHENTICATION_FAILED, "账号不可用");
        }
        String token = sessions.issue(user);
        attempts.clearFailureCount(phone);
        return Result.ok(token);
    }

    private BusinessException recordLoginFailure(String phone) {
        Long failures = attempts.incrementFailureCount(phone);
        if (failures == null) {
            throw new IllegalStateException("登录失败计数未返回结果");
        }
        if (failures >= LOGIN_FAILURE_LIMIT) {
            return loginRateLimited();
        }
        return new BusinessException(ErrorCode.AUTHENTICATION_FAILED, "验证码错误");
    }

    private BusinessException loginRateLimited() {
        return new BusinessException(ErrorCode.RATE_LIMITED, "登录失败次数过多，请稍后再试");
    }
}
