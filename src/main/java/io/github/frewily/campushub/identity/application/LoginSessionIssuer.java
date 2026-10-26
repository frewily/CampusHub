package io.github.frewily.campushub.identity.application;

public interface LoginSessionIssuer {
    String issue(LoginAccount account);
}
