package io.github.frewily.campushub.identity.application;

public interface LoginAccountRepository {
    LoginAccount findByPhone(String phone);

    LoginAccount createWithPhone(String phone);
}
