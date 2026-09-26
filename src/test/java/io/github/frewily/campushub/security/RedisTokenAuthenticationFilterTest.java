package io.github.frewily.campushub.security;

import io.github.frewily.campushub.dto.UserDTO;
import io.github.frewily.campushub.mapper.AccountAccessMapper;
import io.github.frewily.campushub.utils.UserHolder;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.HashOperations;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.core.context.SecurityContextHolder;

import javax.servlet.FilterChain;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import static io.github.frewily.campushub.utils.RedisConstants.LOGIN_USER_KEY;
import static io.github.frewily.campushub.utils.RedisConstants.LOGIN_USER_TTL;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class RedisTokenAuthenticationFilterTest {

    @Mock
    private StringRedisTemplate stringRedisTemplate;
    @Mock
    private HashOperations<String, Object, Object> hashOperations;
    @Mock
    private AccountAccessMapper accountAccessMapper;

    @AfterEach
    void tearDown() {
        UserHolder.removeUser();
        SecurityContextHolder.clearContext();
    }

    @Test
    void shouldLoadCurrentRolesAndClearBothRequestContexts() throws Exception {
        String token = "token-value";
        String tokenKey = LOGIN_USER_KEY + token;
        Map<Object, Object> session = new HashMap<>();
        session.put("id", "7");
        session.put("nickName", "campus-user");
        when(stringRedisTemplate.opsForHash()).thenReturn(hashOperations);
        when(hashOperations.entries(tokenKey)).thenReturn(session);
        when(accountAccessMapper.findAccountStatus(7L)).thenReturn("ACTIVE");
        when(accountAccessMapper.findRoles(7L)).thenReturn(Arrays.asList("USER", "MERCHANT"));
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader("authorization", token);
        FilterChain chain = (servletRequest, servletResponse) -> {
            UserDTO user = UserHolder.getUser();
            assertNotNull(user);
            assertEquals(7L, user.getId());
            assertTrue(SecurityContextHolder.getContext().getAuthentication().getAuthorities().stream()
                    .anyMatch(authority -> "ROLE_MERCHANT".equals(authority.getAuthority())));
        };

        new RedisTokenAuthenticationFilter(stringRedisTemplate, accountAccessMapper)
                .doFilter(request, new MockHttpServletResponse(), chain);

        verify(stringRedisTemplate).expire(tokenKey, LOGIN_USER_TTL, TimeUnit.MINUTES);
        assertNull(UserHolder.getUser());
        assertNull(SecurityContextHolder.getContext().getAuthentication());
    }

    @Test
    void shouldInvalidateSessionForDisabledAccount() throws Exception {
        String tokenKey = LOGIN_USER_KEY + "disabled-token";
        Map<Object, Object> session = new HashMap<>();
        session.put("id", "8");
        when(stringRedisTemplate.opsForHash()).thenReturn(hashOperations);
        when(hashOperations.entries(tokenKey)).thenReturn(session);
        when(accountAccessMapper.findAccountStatus(8L)).thenReturn("DISABLED");
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader("authorization", "disabled-token");

        new RedisTokenAuthenticationFilter(stringRedisTemplate, accountAccessMapper)
                .doFilter(request, new MockHttpServletResponse(), (ignoredRequest, ignoredResponse) ->
                        assertNull(SecurityContextHolder.getContext().getAuthentication()));

        verify(stringRedisTemplate).delete(tokenKey);
        assertNull(UserHolder.getUser());
    }
}
