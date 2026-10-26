package io.github.frewily.campushub.service.impl;

import io.github.frewily.campushub.dto.LoginFormDTO;
import io.github.frewily.campushub.dto.Result;
import io.github.frewily.campushub.identity.application.LoginAccount;
import io.github.frewily.campushub.identity.application.LoginAccountRepository;
import io.github.frewily.campushub.identity.application.LoginSessionIssuer;
import io.github.frewily.campushub.exception.BusinessException;
import io.github.frewily.campushub.exception.ErrorCode;
import io.github.frewily.campushub.service.IUserService;
import io.github.frewily.campushub.utils.RegexUtils;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import javax.annotation.Resource;
import java.util.Collections;
import java.util.concurrent.TimeUnit;

import static io.github.frewily.campushub.utils.RedisConstants.*;

@Service
public class UserServiceImpl implements IUserService {

    private static final DefaultRedisScript<Long> LOGIN_CODE_CONSUME_SCRIPT;
    private static final DefaultRedisScript<Long> LOGIN_FAILURE_INCREMENT_SCRIPT;

    static {
        LOGIN_CODE_CONSUME_SCRIPT = new DefaultRedisScript<>();
        LOGIN_CODE_CONSUME_SCRIPT.setLocation(new ClassPathResource("login-code-consume.lua"));
        LOGIN_CODE_CONSUME_SCRIPT.setResultType(Long.class);

        LOGIN_FAILURE_INCREMENT_SCRIPT = new DefaultRedisScript<>();
        LOGIN_FAILURE_INCREMENT_SCRIPT.setLocation(new ClassPathResource("login-failure-increment.lua"));
        LOGIN_FAILURE_INCREMENT_SCRIPT.setResultType(Long.class);
    }

    @Resource
    private StringRedisTemplate stringRedisTemplate;

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
        String failureKey = LOGIN_FAILURE_KEY + phone;
        String failureCount = stringRedisTemplate.opsForValue().get(failureKey);
        if (failureCount != null && Long.parseLong(failureCount) >= LOGIN_FAILURE_LIMIT) {
            throw loginRateLimited();
        }
        String cacheCode = loginForm.getCode();
        if (cacheCode == null) {
            throw recordLoginFailure(failureKey);
        }
        Long consumed = stringRedisTemplate.execute(
                LOGIN_CODE_CONSUME_SCRIPT,
                Collections.singletonList(LOGIN_CODE_KEY + phone),
                cacheCode
        );
        if (consumed == null) {
            throw new IllegalStateException("验证码校验未返回结果");
        }
        if (consumed != 1L) {
            throw recordLoginFailure(failureKey);
        }
        LoginAccount user = accounts.findByPhone(phone);
        if (user == null) {
            user = accounts.createWithPhone(phone);
        }
        if (!"ACTIVE".equals(user.getStatus())) {
            throw new BusinessException(ErrorCode.AUTHENTICATION_FAILED, "账号不可用");
        }
        String token = sessions.issue(user);
        stringRedisTemplate.delete(failureKey);
        return Result.ok(token);
    }

    private BusinessException recordLoginFailure(String failureKey) {
        Long failures = stringRedisTemplate.execute(
                LOGIN_FAILURE_INCREMENT_SCRIPT,
                Collections.singletonList(failureKey),
                String.valueOf(TimeUnit.MINUTES.toSeconds(LOGIN_FAILURE_TTL))
        );
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
