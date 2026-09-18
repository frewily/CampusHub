package com.hmdp.utils;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PasswordEncoderTest {

    @Test
    void shouldMatchTheOriginalPasswordOnly() {
        String encoded = PasswordEncoder.encode("campus-hub-password");

        assertTrue(PasswordEncoder.matches(encoded, "campus-hub-password"));
        assertFalse(PasswordEncoder.matches(encoded, "wrong-password"));
        assertFalse(PasswordEncoder.matches(null, "campus-hub-password"));
    }
}
