package com.hmdp.utils;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RegexUtilsTest {

    @Test
    void shouldValidatePhoneNumbers() {
        assertFalse(RegexUtils.isPhoneInvalid("13800138000"));
        assertTrue(RegexUtils.isPhoneInvalid("12345"));
        assertTrue(RegexUtils.isPhoneInvalid(null));
    }

    @Test
    void shouldValidateVerificationCodes() {
        assertFalse(RegexUtils.isCodeInvalid("123456"));
        assertTrue(RegexUtils.isCodeInvalid("12345"));
        assertTrue(RegexUtils.isCodeInvalid("abcde!"));
    }
}
