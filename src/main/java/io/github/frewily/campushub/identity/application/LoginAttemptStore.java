package io.github.frewily.campushub.identity.application;

public interface LoginAttemptStore {
    String findFailureCount(String phone);

    Long consumeCode(String phone, String code);

    Long incrementFailureCount(String phone);

    void clearFailureCount(String phone);
}
