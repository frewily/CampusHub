package io.github.frewily.campushub.service.impl;

import io.github.frewily.campushub.dto.LoginFormDTO;
import io.github.frewily.campushub.dto.Result;
import io.github.frewily.campushub.dto.UserDTO;
import io.github.frewily.campushub.entity.User;
import io.github.frewily.campushub.exception.BusinessException;
import io.github.frewily.campushub.exception.ErrorCode;
import io.github.frewily.campushub.mapper.UserMapper;
import io.github.frewily.campushub.utils.UserHolder;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.HashOperations;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Collections;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import static io.github.frewily.campushub.utils.RedisConstants.LOGIN_CODE_COOLDOWN_KEY;
import static io.github.frewily.campushub.utils.RedisConstants.LOGIN_CODE_COOLDOWN_TTL;
import static io.github.frewily.campushub.utils.RedisConstants.LOGIN_CODE_KEY;
import static io.github.frewily.campushub.utils.RedisConstants.LOGIN_CODE_TTL;
import static io.github.frewily.campushub.utils.RedisConstants.LOGIN_FAILURE_KEY;
import static io.github.frewily.campushub.utils.RedisConstants.LOGIN_FAILURE_TTL;
import static io.github.frewily.campushub.utils.RedisConstants.LOGIN_USER_KEY;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@SuppressWarnings("unchecked")
class UserServiceImplTest {

    private static final String PHONE = "13800138000";

    @Mock
    private UserMapper userMapper;
    @Mock
    private StringRedisTemplate stringRedisTemplate;
    @Mock
    private ValueOperations<String, String> valueOperations;
    @Mock
    private HashOperations<String, Object, Object> hashOperations;

    private UserServiceImpl userService;

    @BeforeEach
    void setUp() {
        userService = new UserServiceImpl();
        ReflectionTestUtils.setField(userService, "baseMapper", userMapper);
        ReflectionTestUtils.setField(userService, "stringRedisTemplate", stringRedisTemplate);
    }

    @AfterEach
    void tearDown() {
        UserHolder.removeUser();
    }

    @Test
    void shouldRateLimitRepeatedVerificationCodeRequests() {
        when(stringRedisTemplate.opsForValue()).thenReturn(valueOperations);
        when(valueOperations.setIfAbsent(
                LOGIN_CODE_COOLDOWN_KEY + PHONE,
                "1",
                LOGIN_CODE_COOLDOWN_TTL,
                TimeUnit.SECONDS
        )).thenReturn(false);

        BusinessException exception = assertThrows(BusinessException.class, () -> userService.sendCode(PHONE));

        assertEquals(ErrorCode.RATE_LIMITED, exception.getErrorCode());
        verify(valueOperations, never()).set(
                eq(LOGIN_CODE_KEY + PHONE),
                anyString(),
                eq(LOGIN_CODE_TTL),
                eq(TimeUnit.MINUTES)
        );
    }

    @Test
    void shouldStoreCodeWithoutReturningItWhenCooldownIsAcquired() {
        when(stringRedisTemplate.opsForValue()).thenReturn(valueOperations);
        when(valueOperations.setIfAbsent(
                LOGIN_CODE_COOLDOWN_KEY + PHONE,
                "1",
                LOGIN_CODE_COOLDOWN_TTL,
                TimeUnit.SECONDS
        )).thenReturn(true);
        ArgumentCaptor<String> codeCaptor = ArgumentCaptor.forClass(String.class);

        Result result = userService.sendCode(PHONE);

        assertTrue(result.getSuccess());
        assertEquals("发送验证码成功", result.getData());
        verify(valueOperations).set(
                eq(LOGIN_CODE_KEY + PHONE),
                codeCaptor.capture(),
                eq(LOGIN_CODE_TTL),
                eq(TimeUnit.MINUTES)
        );
        assertTrue(codeCaptor.getValue().matches("\\d{6}"));
    }

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
        User user = new User().setId(7L).setPhone(PHONE).setNickName("campus-user");
        when(userMapper.selectOne(any())).thenReturn(user);
        when(stringRedisTemplate.opsForHash()).thenReturn(hashOperations);

        Result result = userService.login(loginForm("654321"));

        assertTrue(result.getSuccess());
        assertNotNull(result.getData());
        String token = result.getData().toString();
        assertFalse(token.isEmpty());
        ArgumentCaptor<Map<String, Object>> userMapCaptor = ArgumentCaptor.forClass(Map.class);
        verify(hashOperations).putAll(eq(LOGIN_USER_KEY + token), userMapCaptor.capture());
        assertEquals("7", userMapCaptor.getValue().get("id"));
        assertNull(userMapCaptor.getValue().get("phone"));
        verify(stringRedisTemplate).delete(LOGIN_FAILURE_KEY + PHONE);
    }

    @Test
    void shouldRevokeTokenAndClearCurrentUserOnLogout() {
        UserHolder.saveUser(new UserDTO());

        Result result = userService.logout(" token-value ");

        assertTrue(result.getSuccess());
        verify(stringRedisTemplate).delete(LOGIN_USER_KEY + "token-value");
        assertNull(UserHolder.getUser());
    }

    private LoginFormDTO loginForm(String code) {
        LoginFormDTO loginForm = new LoginFormDTO();
        loginForm.setPhone(PHONE);
        loginForm.setCode(code);
        return loginForm;
    }
}
