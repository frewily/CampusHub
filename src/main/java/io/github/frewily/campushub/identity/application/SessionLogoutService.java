package io.github.frewily.campushub.identity.application;

import io.github.frewily.campushub.exception.BusinessException;
import io.github.frewily.campushub.exception.ErrorCode;

public class SessionLogoutService {
    private final SessionTerminator sessions;

    public SessionLogoutService(SessionTerminator sessions) { this.sessions = sessions; }

    public void logout(String token) {
        if (token == null || token.trim().isEmpty()) {
            throw new BusinessException(ErrorCode.AUTHENTICATION_FAILED, "认证信息缺失");
        }
        sessions.terminate(token.trim());
    }
}
