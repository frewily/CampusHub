package io.github.frewily.campushub.security;

import java.util.Optional;

public enum AccountRole {
    USER,
    MERCHANT,
    ADMIN;

    public String authority() {
        return "ROLE_" + name();
    }

    public static Optional<AccountRole> fromDatabaseValue(String value) {
        if (value == null) {
            return Optional.empty();
        }
        try {
            return Optional.of(AccountRole.valueOf(value));
        } catch (IllegalArgumentException ignored) {
            return Optional.empty();
        }
    }
}
