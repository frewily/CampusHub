package io.github.frewily.campushub.service.impl;

import io.github.frewily.campushub.dto.LoginFormDTO;
import io.github.frewily.campushub.dto.Result;
import io.github.frewily.campushub.identity.application.LoginAccount;
import io.github.frewily.campushub.identity.application.LoginAccountRepository;
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
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Collections;
import java.util.concurrent.TimeUnit;

import static io.github.frewily.campushub.utils.RedisConstants.LOGIN_CODE_KEY;
import static io.github.frewily.campushub.utils.RedisConstants.LOGIN_FAILURE_KEY;
import static io.github.frewily.campushub.utils.RedisConstants.LOGIN_FAILURE_TTL;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@SuppressWarnings("unchecked")
class UserServiceImplTest {

    private static final String PHONE = "13800138000";

    @Mock
    private LoginAccountRepository accounts;
    @Mock
    private StringRedisTemplate stringRedisTemplate;
    @Mock
    private ValueOperations<String, String> valueOperations;
    @Mock
    private LoginSessionIssuer sessions;

    private UserServiceImpl userService;

    @BeforeEach
    void setUp() {
        userService = new UserServiceImpl();
        ReflectionTestUtils.setField(userService, "accounts", accounts);
        ReflectionTestUtils.setField(userService, "stringRedisTemplate", stringRedisTemplate);
        ReflectionTestUtils.setField(userService, "sessions", sessions);
    }

    @AfterEach
    void tearDown() { UserHolder.removeUser(); }

    @Test
    void shouldCountFailedLoginAttempts() {
        when(stringRedisTemplate.opsForValue()).thenReturn(valueOperations);
        when(valueOperations.get(LOGIN_FAILURE_KEY + PHONE)).thenReturn(null);
        when(stringRedisTemplate.execute(
                any(DefaultRedisScript.class),
                eq(Collections.singletonList(LOGIN_CODE_KEY + PHONE)),
                eq("000000")
        )).thenReturn(0L);
        when(stringRedisTemplate.execute(
                any(DefaultRedisScript.class),
                eq(Collections.singletonList(LOGIN_FAILURE_KEY + PHONE)),
                eq(String.valueOf(TimeUnit.MINUTES.toSeconds(LOGIN_FAILURE_TTL)))
        )).thenReturn(1L);

        BusinessException exception = assertThrows(
                BusinessException.class,
                () -> userService.login(loginForm("000000"))
        );

        assertEquals(ErrorCode.AUTHENTICATION_FAILED, exception.getErrorCode());
        verify(stringRedisTemplate).execute(
                any(DefaultRedisScript.class),
                eq(Collections.singletonList(LOGIN_FAILURE_KEY + PHONE)),
                eq("600")
        );
    }

    @Test
    void shouldRateLimitTheFifthFailedLoginAttempt() {
        when(stringRedisTemplate.opsForValue()).thenReturn(valueOperations);
        when(valueOperations.get(LOGIN_FAILURE_KEY + PHONE)).thenReturn("4");
        when(stringRedisTemplate.execute(
                any(DefaultRedisScript.class),
                eq(Collections.singletonList(LOGIN_CODE_KEY + PHONE)),
                eq("000000")
        )).thenReturn(0L);
        when(stringRedisTemplate.execute(
                any(DefaultRedisScript.class),
                eq(Collections.singletonList(LOGIN_FAILURE_KEY + PHONE)),
                eq(String.valueOf(TimeUnit.MINUTES.toSeconds(LOGIN_FAILURE_TTL)))
        )).thenReturn(5L);

        BusinessException exception = assertThrows(
                BusinessException.class,
                () -> userService.login(loginForm("000000"))
        );

        assertEquals(ErrorCode.RATE_LIMITED, exception.getErrorCode());
    }

    @Test
    void shouldConsumeCodeAndResetFailuresAfterSuccessfulLogin() {
        when(stringRedisTemplate.opsForValue()).thenReturn(valueOperations);
        when(valueOperations.get(LOGIN_FAILURE_KEY + PHONE)).thenReturn(null);
        when(stringRedisTemplate.execute(
                any(DefaultRedisScript.class),
                eq(Collections.singletonList(LOGIN_CODE_KEY + PHONE)),
                eq("654321")
        )).thenReturn(1L);
        when(accounts.findByPhone(PHONE)).thenReturn(new LoginAccount(7L, "campus-user", "", "ACTIVE"));
        when(sessions.issue(any(LoginAccount.class))).thenReturn("session-token");

        Result result = userService.login(loginForm("654321"));

        assertTrue(result.getSuccess());
        assertNotNull(result.getData());
        assertFalse(result.getData().toString().isEmpty());
        assertEquals("session-token", result.getData());
        verify(sessions).issue(any(LoginAccount.class));
        verify(stringRedisTemplate).delete(LOGIN_FAILURE_KEY + PHONE);
    }

    @Test
    void shouldRejectDisabledAccountWithoutCreatingSession() {
        when(stringRedisTemplate.opsForValue()).thenReturn(valueOperations);
        when(valueOperations.get(LOGIN_FAILURE_KEY + PHONE)).thenReturn(null);
        when(stringRedisTemplate.execute(
                any(DefaultRedisScript.class),
                eq(Collections.singletonList(LOGIN_CODE_KEY + PHONE)),
                eq("654321")
        )).thenReturn(1L);
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
        when(stringRedisTemplate.opsForValue()).thenReturn(valueOperations);
        when(valueOperations.get(LOGIN_FAILURE_KEY + PHONE)).thenReturn(null);
        when(stringRedisTemplate.execute(
                any(DefaultRedisScript.class),
                eq(Collections.singletonList(LOGIN_CODE_KEY + PHONE)),
                eq("654321")
        )).thenReturn(1L);
        when(accounts.findByPhone(PHONE)).thenReturn(null);
        when(accounts.createWithPhone(PHONE)).thenReturn(new LoginAccount(9L, "USER-new-user", "", "ACTIVE"));
        when(sessions.issue(any(LoginAccount.class))).thenReturn("session-token");

        Result result = userService.login(loginForm("654321"));

        assertTrue(result.getSuccess());
        verify(accounts).createWithPhone(PHONE);
    }

    @Test
    void issuerFailureLeavesFailureCounterUntouched() {
        when(stringRedisTemplate.opsForValue()).thenReturn(valueOperations);
        when(valueOperations.get(LOGIN_FAILURE_KEY + PHONE)).thenReturn("2");
        when(stringRedisTemplate.execute(any(DefaultRedisScript.class), eq(Collections.singletonList(LOGIN_CODE_KEY + PHONE)), eq("654321"))).thenReturn(1L);
        when(accounts.findByPhone(PHONE)).thenReturn(new LoginAccount(7L, "campus-user", "", "ACTIVE"));
        when(sessions.issue(any(LoginAccount.class))).thenThrow(new IllegalStateException("issuer failure"));

        assertThrows(IllegalStateException.class, () -> userService.login(loginForm("654321")));
        verify(stringRedisTemplate, never()).delete(LOGIN_FAILURE_KEY + PHONE);
    }

    @Test
    void failureCounterCleanupErrorDoesNotRevokeIssuedSession() {
        when(stringRedisTemplate.opsForValue()).thenReturn(valueOperations);
        when(valueOperations.get(LOGIN_FAILURE_KEY + PHONE)).thenReturn("2");
        when(stringRedisTemplate.execute(any(DefaultRedisScript.class), eq(Collections.singletonList(LOGIN_CODE_KEY + PHONE)), eq("654321"))).thenReturn(1L);
        LoginAccount account = new LoginAccount(7L, "campus-user", "", "ACTIVE");
        when(accounts.findByPhone(PHONE)).thenReturn(account);
        when(sessions.issue(account)).thenReturn("session-token");
        when(stringRedisTemplate.delete(LOGIN_FAILURE_KEY + PHONE)).thenThrow(new IllegalStateException("cleanup failure"));

        assertThrows(IllegalStateException.class, () -> userService.login(loginForm("654321")));
        verify(sessions).issue(account);
    }

    private LoginFormDTO loginForm(String code) {
        LoginFormDTO loginForm = new LoginFormDTO();
        loginForm.setPhone(PHONE);
        loginForm.setCode(code);
        return loginForm;
    }
}
