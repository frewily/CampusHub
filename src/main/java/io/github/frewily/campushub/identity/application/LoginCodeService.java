package io.github.frewily.campushub.identity.application;

import io.github.frewily.campushub.exception.BusinessException;
import io.github.frewily.campushub.exception.ErrorCode;

import static io.github.frewily.campushub.utils.RegexPatterns.PHONE_REGEX;

public class LoginCodeService {
    private final LoginCodeStore store;
    private final LoginCodeGenerator generator;

    public LoginCodeService(LoginCodeStore store, LoginCodeGenerator generator) {
        this.store = store;
        this.generator = generator;
    }

    public void sendCode(String phone) {
        // Reuse the legacy pure-Java pattern; matching already rejects every null/blank input.
        if (phone == null || !phone.matches(PHONE_REGEX)) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED, "手机号格式错误");
        }
        if (!Boolean.TRUE.equals(store.acquireCooldown(phone))) {
            throw new BusinessException(ErrorCode.RATE_LIMITED, "验证码发送过于频繁，请稍后再试");
        }
        // Keep generation outside the save-failure handler, as in the original implementation.
        String code = generator.generate();
        try {
            store.saveCode(phone, code);
        } catch (RuntimeException failure) {
            store.releaseCooldown(phone);
            throw failure;
        }
    }
}
