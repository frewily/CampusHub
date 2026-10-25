package io.github.frewily.campushub.identity.application;

import io.github.frewily.campushub.exception.BusinessException;
import io.github.frewily.campushub.exception.ErrorCode;
import io.github.frewily.campushub.utils.RegexUtils;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class LoginCodeServiceTest {
    private static final String PHONE = "13800138000";

    @Test void validatesWithLegacyRegexAndPreservesInputExactly() {
        LoginCodeStore store = mock(LoginCodeStore.class);
        LoginCodeGenerator generator = mock(LoginCodeGenerator.class);
        when(store.acquireCooldown(PHONE)).thenReturn(true);
        when(generator.generate()).thenReturn("123456");
        new LoginCodeService(store, generator).sendCode(PHONE);
        verify(store).saveCode(PHONE, "123456");
        assertFalse(RegexUtils.isPhoneInvalid("13800138000"));
        assertTrue(RegexUtils.isPhoneInvalid(null));
        assertTrue(RegexUtils.isPhoneInvalid("   "));
        assertTrue(RegexUtils.isPhoneInvalid("13800138000\n"));

        for (String invalid : new String[]{null, "", "   ", "13800138000\n", "13800138000,"}) {
            BusinessException ex = assertThrows(BusinessException.class,
                    () -> new LoginCodeService(store, generator).sendCode(invalid));
            assertEquals(ErrorCode.VALIDATION_FAILED, ex.getErrorCode());
            assertEquals("手机号格式错误", ex.getMessage());
        }
        String padded = " 13800138000 ";
        assertThrows(BusinessException.class, () -> new LoginCodeService(store, generator).sendCode(padded));
        verify(store, never()).acquireCooldown(padded);
    }

    @Test void oldRegexCommaCharacterClassBoundaryRemainsAccepted() {
        String legacyCommaNumber = "15,12345678";
        assertFalse(RegexUtils.isPhoneInvalid(legacyCommaNumber));
        LoginCodeStore store = mock(LoginCodeStore.class);
        when(store.acquireCooldown(legacyCommaNumber)).thenReturn(true);
        new LoginCodeService(store, () -> "000001").sendCode(legacyCommaNumber);
        verify(store).saveCode(legacyCommaNumber, "000001");
    }

    @Test void cooldownFalseOrNullRejectsWithoutGenerating() {
        for (Boolean acquired : new Boolean[]{false, null}) {
            LoginCodeStore store = mock(LoginCodeStore.class);
            LoginCodeGenerator generator = mock(LoginCodeGenerator.class);
            when(store.acquireCooldown(PHONE)).thenReturn(acquired);
            BusinessException ex = assertThrows(BusinessException.class,
                    () -> new LoginCodeService(store, generator).sendCode(PHONE));
            assertEquals(ErrorCode.RATE_LIMITED, ex.getErrorCode());
            assertEquals("验证码发送过于频繁，请稍后再试", ex.getMessage());
            verifyNoInteractions(generator);
            verify(store, never()).saveCode(anyString(), anyString());
            verify(store, never()).releaseCooldown(anyString());
        }
    }

    @Test void acquireFailureAndGeneratorFailureKeepAcquiredCooldown() {
        LoginCodeStore acquireFailure = mock(LoginCodeStore.class);
        RuntimeException setnx = new IllegalStateException("acquire");
        when(acquireFailure.acquireCooldown(PHONE)).thenThrow(setnx);
        assertSame(setnx, assertThrows(RuntimeException.class,
                () -> new LoginCodeService(acquireFailure, () -> "x").sendCode(PHONE)));
        verify(acquireFailure, never()).releaseCooldown(anyString());

        LoginCodeStore generatorFailure = mock(LoginCodeStore.class);
        when(generatorFailure.acquireCooldown(PHONE)).thenReturn(true);
        RuntimeException generation = new IllegalArgumentException("generate");
        assertSame(generation, assertThrows(RuntimeException.class,
                () -> new LoginCodeService(generatorFailure, () -> { throw generation; }).sendCode(PHONE)));
        verify(generatorFailure, never()).releaseCooldown(anyString());
    }

    @Test void saveFailureReleasesCooldownAndReleaseFailureOverridesSaveFailure() {
        LoginCodeStore store = mock(LoginCodeStore.class);
        when(store.acquireCooldown(PHONE)).thenReturn(true);
        RuntimeException save = new IllegalStateException("save");
        doThrow(save).when(store).saveCode(PHONE, "123456");
        assertSame(save, assertThrows(RuntimeException.class,
                () -> new LoginCodeService(store, () -> "123456").sendCode(PHONE)));
        verify(store).releaseCooldown(PHONE);

        LoginCodeStore releaseFails = mock(LoginCodeStore.class);
        when(releaseFails.acquireCooldown(PHONE)).thenReturn(true);
        RuntimeException release = new IllegalArgumentException("release");
        doThrow(save).when(releaseFails).saveCode(PHONE, "123456");
        doThrow(release).when(releaseFails).releaseCooldown(PHONE);
        assertSame(release, assertThrows(RuntimeException.class,
                () -> new LoginCodeService(releaseFails, () -> "123456").sendCode(PHONE)));
    }
}
