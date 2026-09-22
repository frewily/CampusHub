package io.github.frewily.campushub.utils;

import io.github.frewily.campushub.dto.UserDTO;
import io.github.frewily.campushub.exception.BusinessException;
import io.github.frewily.campushub.exception.ErrorCode;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.HashOperations;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import static io.github.frewily.campushub.utils.RedisConstants.LOGIN_USER_KEY;
import static io.github.frewily.campushub.utils.RedisConstants.LOGIN_USER_TTL;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AuthInterceptorTest {

    @Mock
    private StringRedisTemplate stringRedisTemplate;
    @Mock
    private HashOperations<String, Object, Object> hashOperations;

    @AfterEach
    void tearDown() {
        UserHolder.removeUser();
    }

    @Test
    void shouldClearStaleUserAtTheStartOfAnonymousRequest() throws Exception {
        UserHolder.saveUser(new UserDTO());
        RefreshTokenInterceptor interceptor = new RefreshTokenInterceptor(stringRedisTemplate);

        boolean allowed = interceptor.preHandle(
                new MockHttpServletRequest(),
                new MockHttpServletResponse(),
                new Object()
        );

        assertTrue(allowed);
        assertNull(UserHolder.getUser());
    }

    @Test
    void shouldLoadRefreshAndClearAuthenticatedUser() throws Exception {
        String token = "token-value";
        Map<Object, Object> userMap = new HashMap<>();
        userMap.put("id", "7");
        userMap.put("nickName", "campus-user");
        when(stringRedisTemplate.opsForHash()).thenReturn(hashOperations);
        when(hashOperations.entries(LOGIN_USER_KEY + token)).thenReturn(userMap);
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader("authorization", token);
        MockHttpServletResponse response = new MockHttpServletResponse();
        RefreshTokenInterceptor interceptor = new RefreshTokenInterceptor(stringRedisTemplate);

        assertTrue(interceptor.preHandle(request, response, new Object()));
        assertEquals(7L, UserHolder.getUser().getId());
        verify(stringRedisTemplate).expire(
                LOGIN_USER_KEY + token,
                LOGIN_USER_TTL,
                TimeUnit.MINUTES
        );

        interceptor.afterCompletion(request, response, new Object(), null);

        assertNull(UserHolder.getUser());
    }

    @Test
    void shouldUseTypedAuthenticationFailureForAnonymousProtectedRequest() {
        LoginInterceptor interceptor = new LoginInterceptor();

        BusinessException exception = assertThrows(
                BusinessException.class,
                () -> interceptor.preHandle(
                        new MockHttpServletRequest(),
                        new MockHttpServletResponse(),
                        new Object()
                )
        );

        assertEquals(ErrorCode.AUTHENTICATION_FAILED, exception.getErrorCode());
    }
}
