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
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.core.context.SecurityContextHolder;

import javax.servlet.FilterChain;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import static io.github.frewily.campushub.utils.RedisConstants.LOGIN_USER_KEY;
import static io.github.frewily.campushub.utils.RedisConstants.LOGIN_USER_TTL;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class RedisTokenAuthenticationFilterTest {
    @Mock private StringRedisTemplate redis;
    @Mock private HashOperations<String, Object, Object> hashes;
    @Mock private AccountAccessMapper accounts;

    @AfterEach void tearDown() {
        UserHolder.removeUser();
        SecurityContextHolder.clearContext();
    }

    @Test void shouldLoadCurrentRolesAndClearBothRequestContexts() throws Exception {
        String token = "token-value", key = LOGIN_USER_KEY + token;
        Map<Object, Object> map = session("7", "campus-user");
        arrange(key, map, 7L, Arrays.asList("USER", "MERCHANT"));
        when(redis.execute(any(DefaultRedisScript.class), eq(Collections.singletonList(key)),
                eq("7"), eq(String.valueOf(TimeUnit.MINUTES.toMillis(LOGIN_USER_TTL)))))
                .thenAnswer(invocation -> {
                    assertNull(UserHolder.getUser());
                    assertNull(SecurityContextHolder.getContext().getAuthentication());
                    return 1L;
                });
        MockHttpServletRequest request = request(token);
        FilterChain chain = (a, b) -> {
            UserDTO user = UserHolder.getUser();
            assertNotNull(user);
            assertEquals(7L, user.getId());
            assertTrue(SecurityContextHolder.getContext().getAuthentication().getAuthorities().stream()
                    .anyMatch(authority -> "ROLE_MERCHANT".equals(authority.getAuthority())));
        };

        new RedisTokenAuthenticationFilter(redis, accounts).doFilter(request, new MockHttpServletResponse(), chain);

        verify(redis).execute(any(DefaultRedisScript.class), eq(Collections.singletonList(key)),
                eq("7"), eq(String.valueOf(TimeUnit.MINUTES.toMillis(LOGIN_USER_TTL))));
        verify(redis, never()).expire(anyString(), anyLong(), any(TimeUnit.class));
        assertNull(UserHolder.getUser());
        assertNull(SecurityContextHolder.getContext().getAuthentication());
    }

    @Test void shouldNotAuthenticateWhenScriptResultIsNotOne() throws Exception {
        for (Long result : Arrays.asList(0L, null, 2L, -1L)) {
            String key = LOGIN_USER_KEY + "result-" + result;
            arrange(key, session("8", "campus-user"), 8L, Collections.singletonList("USER"));
            when(redis.execute(any(DefaultRedisScript.class), eq(Collections.singletonList(key)),
                    eq("8"), eq(String.valueOf(TimeUnit.MINUTES.toMillis(LOGIN_USER_TTL)))))
                    .thenReturn(result);
            final boolean[] invoked = {false};
            new RedisTokenAuthenticationFilter(redis, accounts).doFilter(request("result-" + result),
                    new MockHttpServletResponse(), (a, b) -> {
                        invoked[0] = true;
                        assertNull(UserHolder.getUser());
                        assertNull(SecurityContextHolder.getContext().getAuthentication());
                    });
            assertTrue(invoked[0]);
            assertNull(UserHolder.getUser());
            assertNull(SecurityContextHolder.getContext().getAuthentication());
        }
    }

    @Test void shouldPropagateScriptFailureAndClearBothContexts() throws Exception {
        String key = LOGIN_USER_KEY + "failure";
        arrange(key, session("9", "campus-user"), 9L, Collections.singletonList("USER"));
        RuntimeException failure = new IllegalStateException("redis unavailable");
        when(redis.execute(any(DefaultRedisScript.class), eq(Collections.singletonList(key)),
                eq("9"), eq(String.valueOf(TimeUnit.MINUTES.toMillis(LOGIN_USER_TTL))))).thenThrow(failure);
        UserHolder.saveUser(new UserDTO());
        SecurityContextHolder.getContext().setAuthentication(
                new org.springframework.security.authentication.UsernamePasswordAuthenticationToken("stale", "x"));
        assertSame(failure, assertThrows(RuntimeException.class, () ->
                new RedisTokenAuthenticationFilter(redis, accounts).doFilter(request("failure"),
                        new MockHttpServletResponse(), (a, b) -> fail("chain must not run"))));
        assertNull(UserHolder.getUser());
        assertNull(SecurityContextHolder.getContext().getAuthentication());
    }

    @Test void shouldPreserveRawRedisIdAndTrimAuthorizationHeader() throws Exception {
        for (String rawId : Arrays.asList("9007199254748993", "0007")) {
            String token = "  raw-id  ", key = LOGIN_USER_KEY + "raw-id";
            arrange(key, session(rawId, "campus-user"), Long.valueOf(rawId), Collections.singletonList("USER"));
            when(redis.execute(any(DefaultRedisScript.class), eq(Collections.singletonList(key)),
                    eq(rawId), eq(String.valueOf(TimeUnit.MINUTES.toMillis(LOGIN_USER_TTL)))))
                    .thenReturn(1L);
            new RedisTokenAuthenticationFilter(redis, accounts).doFilter(request(token),
                    new MockHttpServletResponse(), (a, b) -> assertNotNull(UserHolder.getUser()));
            verify(redis).execute(any(DefaultRedisScript.class), eq(Collections.singletonList(key)),
                    eq(rawId), eq(String.valueOf(TimeUnit.MINUTES.toMillis(LOGIN_USER_TTL))));
        }
    }

    @Test void shouldSkipScriptWithoutSessionAndDeleteDisabledOrRolelessSessions() throws Exception {
        when(redis.opsForHash()).thenReturn(hashes);
        when(hashes.entries(LOGIN_USER_KEY + "missing")).thenReturn(Collections.emptyMap());
        new RedisTokenAuthenticationFilter(redis, accounts).doFilter(request("missing"), new MockHttpServletResponse(),
                (a, b) -> assertNull(UserHolder.getUser()));
        verify(redis, never()).execute(any(DefaultRedisScript.class), anyList(), anyString(), anyString());

        String disabled = LOGIN_USER_KEY + "disabled";
        arrange(disabled, session("8", "disabled"), 8L, null);
        when(accounts.findAccountStatus(8L)).thenReturn("DISABLED");
        new RedisTokenAuthenticationFilter(redis, accounts).doFilter(request("disabled"), new MockHttpServletResponse(),
                (a, b) -> assertNull(SecurityContextHolder.getContext().getAuthentication()));
        verify(redis).delete(disabled);
        verify(redis, never()).execute(any(DefaultRedisScript.class), eq(Collections.singletonList(disabled)), anyString(), anyString());

        String roleless = LOGIN_USER_KEY + "roleless";
        arrange(roleless, session("10", "roleless"), 10L, Collections.<String>emptyList());
        when(accounts.findAccountStatus(10L)).thenReturn("ACTIVE");
        when(accounts.findRoles(10L)).thenReturn(Collections.<String>emptyList());
        new RedisTokenAuthenticationFilter(redis, accounts).doFilter(request("roleless"), new MockHttpServletResponse(),
                (a, b) -> assertNull(UserHolder.getUser()));
        verify(redis).delete(roleless);
        verify(redis, never()).execute(any(DefaultRedisScript.class), eq(Collections.singletonList(roleless)), anyString(), anyString());
    }

    private void arrange(String key, Map<Object, Object> map, Long id, java.util.List<String> roles) {
        when(redis.opsForHash()).thenReturn(hashes);
        when(hashes.entries(key)).thenReturn(map);
        when(accounts.findAccountStatus(id)).thenReturn("ACTIVE");
        if (roles != null) when(accounts.findRoles(id)).thenReturn(roles);
    }
    private static Map<Object, Object> session(String id, String name) {
        Map<Object, Object> map = new HashMap<>(); map.put("id", id); map.put("nickName", name); return map;
    }
    private static MockHttpServletRequest request(String token) {
        MockHttpServletRequest request = new MockHttpServletRequest(); request.addHeader("authorization", token); return request;
    }
}
