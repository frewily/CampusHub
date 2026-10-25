package io.github.frewily.campushub.identity.application;

public interface LoginCodeStore {
    Boolean acquireCooldown(String phone);

    void saveCode(String phone, String code);

    void releaseCooldown(String phone);
}
