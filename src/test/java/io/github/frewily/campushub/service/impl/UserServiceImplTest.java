package io.github.frewily.campushub.service.impl;

import io.github.frewily.campushub.dto.LoginFormDTO;
import io.github.frewily.campushub.dto.Result;
import io.github.frewily.campushub.identity.application.LoginAccount;
import io.github.frewily.campushub.identity.application.LoginAccountRepository;
import io.github.frewily.campushub.identity.application.LoginAttemptStore;
import io.github.frewily.campushub.identity.application.LoginSessionIssuer;
import io.github.frewily.campushub.exception.BusinessException;
import io.github.frewily.campushub.exception.ErrorCode;
import io.github.frewily.campushub.utils.UserHolder;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class UserServiceImplTest {

    private static final String PHONE = "13800138000";

    @Mock
    private LoginAccountRepository accounts;
    @Mock
    private LoginAttemptStore attempts;
    @Mock
    private LoginSessionIssuer sessions;

    private UserServiceImpl userService;

    @BeforeEach
    void setUp() {
        userService = new UserServiceImpl();
        ReflectionTestUtils.setField(userService, "accounts", accounts);
        ReflectionTestUtils.setField(userService, "attempts", attempts);
        ReflectionTestUtils.setField(userService, "sessions", sessions);
    }

    @AfterEach
    void tearDown() { UserHolder.removeUser(); }

    @Test
    void shouldCountFailedLoginAttempts() {
        when(attempts.findFailureCount(PHONE)).thenReturn(null);
        when(attempts.consumeCode(PHONE, "000000")).thenReturn(0L);
        when(attempts.incrementFailureCount(PHONE)).thenReturn(1L);

        BusinessException exception = assertThrows(
                BusinessException.class,
                () -> userService.login(loginForm("000000"))
        );

        assertEquals(ErrorCode.AUTHENTICATION_FAILED, exception.getErrorCode());
        verify(attempts).incrementFailureCount(PHONE);
    }

    @Test
    void shouldRateLimitTheFifthFailedLoginAttempt() {
        when(attempts.findFailureCount(PHONE)).thenReturn("4");
        when(attempts.consumeCode(PHONE, "000000")).thenReturn(0L);
        when(attempts.incrementFailureCount(PHONE)).thenReturn(5L);

        BusinessException exception = assertThrows(
                BusinessException.class,
                () -> userService.login(loginForm("000000"))
        );

        assertEquals(ErrorCode.RATE_LIMITED, exception.getErrorCode());
    }

    @Test
    void shouldConsumeCodeAndResetFailuresAfterSuccessfulLogin() {
        when(attempts.findFailureCount(PHONE)).thenReturn(null);
        when(attempts.consumeCode(PHONE, "654321")).thenReturn(1L);
        when(accounts.findByPhone(PHONE)).thenReturn(new LoginAccount(7L, "campus-user", "", "ACTIVE"));
        when(sessions.issue(any(LoginAccount.class))).thenReturn("session-token");

        Result result = userService.login(loginForm("654321"));

        assertTrue(result.getSuccess());
        assertNotNull(result.getData());
        assertFalse(result.getData().toString().isEmpty());
        assertEquals("session-token", result.getData());
        verify(sessions).issue(any(LoginAccount.class));
        verify(attempts).clearFailureCount(PHONE);
    }

    @Test
    void shouldRejectDisabledAccountWithoutCreatingSession() {
        when(attempts.findFailureCount(PHONE)).thenReturn(null);
        when(attempts.consumeCode(PHONE, "654321")).thenReturn(1L);
        when(accounts.findByPhone(PHONE)).thenReturn(new LoginAccount(7L, "disabled", "", "DISABLED"));

        BusinessException exception = assertThrows(
                BusinessException.class,
                () -> userService.login(loginForm("654321"))
        );

        assertEquals(ErrorCode.AUTHENTICATION_FAILED, exception.getErrorCode());
        org.mockito.Mockito.verifyNoInteractions(sessions);
    }

    @Test
    void shouldCreateAccountWhenNoAccountExists() {
        when(attempts.findFailureCount(PHONE)).thenReturn(null);
        when(attempts.consumeCode(PHONE, "654321")).thenReturn(1L);
        when(accounts.findByPhone(PHONE)).thenReturn(null);
        when(accounts.createWithPhone(PHONE)).thenReturn(new LoginAccount(9L, "USER-new-user", "", "ACTIVE"));
        when(sessions.issue(any(LoginAccount.class))).thenReturn("session-token");

        Result result = userService.login(loginForm("654321"));

        assertTrue(result.getSuccess());
        verify(accounts).createWithPhone(PHONE);
    }

    @Test
    void issuerFailureLeavesFailureCounterUntouched() {
        when(attempts.findFailureCount(PHONE)).thenReturn("2");
        when(attempts.consumeCode(PHONE, "654321")).thenReturn(1L);
        when(accounts.findByPhone(PHONE)).thenReturn(new LoginAccount(7L, "campus-user", "", "ACTIVE"));
        when(sessions.issue(any(LoginAccount.class))).thenThrow(new IllegalStateException("issuer failure"));

        assertThrows(IllegalStateException.class, () -> userService.login(loginForm("654321")));
        verify(attempts, never()).clearFailureCount(PHONE);
    }

    @Test
    void failureCounterCleanupErrorDoesNotRevokeIssuedSession() {
        when(attempts.findFailureCount(PHONE)).thenReturn("2");
        when(attempts.consumeCode(PHONE, "654321")).thenReturn(1L);
        LoginAccount account = new LoginAccount(7L, "campus-user", "", "ACTIVE");
        when(accounts.findByPhone(PHONE)).thenReturn(account);
        when(sessions.issue(account)).thenReturn("session-token");
        org.mockito.Mockito.doThrow(new IllegalStateException("cleanup failure")).when(attempts).clearFailureCount(PHONE);

        assertThrows(IllegalStateException.class, () -> userService.login(loginForm("654321")));
        verify(sessions).issue(account);
    }

    @Test
    void successfulLoginRetainsStorageAccountAndSessionOrder() {
        when(attempts.consumeCode(PHONE, "654321")).thenReturn(1L);
        LoginAccount account = new LoginAccount(7L, "campus-user", "", "ACTIVE");
        when(accounts.findByPhone(PHONE)).thenReturn(account);
        when(sessions.issue(account)).thenReturn("session-token");
        assertEquals("session-token", userService.login(loginForm("654321")).getData());
        org.mockito.InOrder order = org.mockito.Mockito.inOrder(attempts, accounts, sessions);
        order.verify(attempts).findFailureCount(PHONE);
        order.verify(attempts).consumeCode(PHONE, "654321");
        order.verify(accounts).findByPhone(PHONE);
        order.verify(sessions).issue(account);
        order.verify(attempts).clearFailureCount(PHONE);
        verify(attempts, never()).incrementFailureCount(PHONE);
    }

    @Test
    void invalidPhoneIsRejectedBeforeStorageAndIsNotTrimmed() {
        for (String phone : new String[]{null, "", " " + PHONE, PHONE + " "}) {
            LoginFormDTO form = loginForm("654321");
            form.setPhone(phone);
            assertEquals(ErrorCode.VALIDATION_FAILED,
                    assertThrows(BusinessException.class, () -> userService.login(form)).getErrorCode());
        }
        org.mockito.Mockito.verifyNoInteractions(attempts, accounts, sessions);
    }

    @Test
    void existingLimitRejectsBeforeConsumptionOrIncrement() {
        for (String count : new String[]{"5", "6"}) {
            when(attempts.findFailureCount(PHONE)).thenReturn(count);
            assertEquals(ErrorCode.RATE_LIMITED,
                    assertThrows(BusinessException.class, () -> userService.login(loginForm("654321"))).getErrorCode());
        }
        verify(attempts, never()).consumeCode(any(), any());
        verify(attempts, never()).incrementFailureCount(any());
        org.mockito.Mockito.verifyNoInteractions(accounts, sessions);
    }

    @Test
    void malformedFailureCountRetainsParsingFailure() {
        when(attempts.findFailureCount(PHONE)).thenReturn("not-a-number");
        assertThrows(NumberFormatException.class, () -> userService.login(loginForm("654321")));
        verify(attempts, never()).consumeCode(any(), any());
        org.mockito.Mockito.verifyNoInteractions(accounts, sessions);
    }

    @Test
    void missingCodeCountsFailureWithoutConsumption() {
        when(attempts.incrementFailureCount(PHONE)).thenReturn(1L);
        assertEquals(ErrorCode.AUTHENTICATION_FAILED,
                assertThrows(BusinessException.class, () -> userService.login(loginForm(null))).getErrorCode());
        verify(attempts, never()).consumeCode(any(), any());
        verify(attempts).incrementFailureCount(PHONE);
        org.mockito.Mockito.verifyNoInteractions(accounts, sessions);
    }

    @Test
    void nullConsumptionResultIsDependencyFailureWithoutIncrement() {
        when(attempts.consumeCode(PHONE, "654321")).thenReturn(null);
        assertEquals("验证码校验未返回结果",
                assertThrows(IllegalStateException.class, () -> userService.login(loginForm("654321"))).getMessage());
        verify(attempts, never()).incrementFailureCount(any());
        org.mockito.Mockito.verifyNoInteractions(accounts, sessions);
    }

    @Test
    void everyNonOneConsumptionResultCountsFailure() {
        for (Long result : new Long[]{0L, -1L, 2L}) {
            when(attempts.consumeCode(PHONE, "654321")).thenReturn(result);
            when(attempts.incrementFailureCount(PHONE)).thenReturn(1L);
            assertEquals(ErrorCode.AUTHENTICATION_FAILED,
                    assertThrows(BusinessException.class, () -> userService.login(loginForm("654321"))).getErrorCode());
        }
        verify(attempts, org.mockito.Mockito.times(3)).incrementFailureCount(PHONE);
        org.mockito.Mockito.verifyNoInteractions(accounts, sessions);
    }

    @Test
    void nullFailureIncrementRemainsDependencyFailure() {
        when(attempts.incrementFailureCount(PHONE)).thenReturn(null);
        assertEquals("登录失败计数未返回结果",
                assertThrows(IllegalStateException.class, () -> userService.login(loginForm(null))).getMessage());
        org.mockito.Mockito.verifyNoInteractions(accounts, sessions);
    }

    @Test
    void countReadFailureStopsBeforeCodeConsumption() {
        RuntimeException failure = new IllegalStateException("read failure");
        when(attempts.findFailureCount(PHONE)).thenThrow(failure);
        org.junit.jupiter.api.Assertions.assertSame(failure,
                assertThrows(IllegalStateException.class, () -> userService.login(loginForm("654321"))));
        verify(attempts, never()).consumeCode(any(), any());
        org.mockito.Mockito.verifyNoInteractions(accounts, sessions);
    }

    @Test
    void consumptionFailureIsNotConvertedToWrongCodeOrCounted() {
        RuntimeException failure = new IllegalStateException("consume failure");
        when(attempts.consumeCode(PHONE, "654321")).thenThrow(failure);
        org.junit.jupiter.api.Assertions.assertSame(failure,
                assertThrows(IllegalStateException.class, () -> userService.login(loginForm("654321"))));
        verify(attempts, never()).incrementFailureCount(any());
        org.mockito.Mockito.verifyNoInteractions(accounts, sessions);
    }

    @Test
    void incrementFailureIsNotConvertedToAuthenticationFailure() {
        RuntimeException failure = new IllegalStateException("increment failure");
        when(attempts.incrementFailureCount(PHONE)).thenThrow(failure);
        org.junit.jupiter.api.Assertions.assertSame(failure,
                assertThrows(IllegalStateException.class, () -> userService.login(loginForm(null))));
        org.mockito.Mockito.verifyNoInteractions(accounts, sessions);
    }

    @Test
    void accountFailureAfterConsumptionDoesNotClearFailuresOrIssueSession() {
        RuntimeException failure = new IllegalStateException("account failure");
        when(attempts.consumeCode(PHONE, "654321")).thenReturn(1L);
        when(accounts.findByPhone(PHONE)).thenThrow(failure);
        org.junit.jupiter.api.Assertions.assertSame(failure,
                assertThrows(IllegalStateException.class, () -> userService.login(loginForm("654321"))));
        verify(attempts).consumeCode(PHONE, "654321");
        verify(attempts, never()).clearFailureCount(PHONE);
        org.mockito.Mockito.verifyNoInteractions(sessions);
    }

    private LoginFormDTO loginForm(String code) {
        LoginFormDTO loginForm = new LoginFormDTO();
        loginForm.setPhone(PHONE);
        loginForm.setCode(code);
        return loginForm;
    }
}
