package io.github.frewily.campushub.identity.api;

import io.github.frewily.campushub.config.GlobalExceptionHandler;
import io.github.frewily.campushub.config.SecurityConfig;
import io.github.frewily.campushub.identity.config.SessionLogoutConfiguration;
import io.github.frewily.campushub.identity.infrastructure.RedisSessionTerminator;
import io.github.frewily.campushub.mapper.AccountAccessMapper;
import io.github.frewily.campushub.security.RedisTokenAuthenticationFilter;
import io.github.frewily.campushub.security.SecurityErrorResponder;
import io.github.frewily.campushub.security.ResourceAuthorizationService;
import io.github.frewily.campushub.utils.UserHolder;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.data.redis.core.HashOperations;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.test.web.servlet.MockMvc;

import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

import static io.github.frewily.campushub.utils.RedisConstants.LOGIN_USER_KEY;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(classes = LogoutHttpContractTest.TestApplication.class)
@AutoConfigureMockMvc
class LogoutHttpContractTest {
    @Autowired private MockMvc mvc;
    @MockBean private StringRedisTemplate redis;
    @MockBean private HashOperations<String, Object, Object> hashes;
    @MockBean private AccountAccessMapper accounts;

    @SpringBootConfiguration @EnableAutoConfiguration
    @Import({LogoutController.class, RedisSessionTerminator.class, SessionLogoutConfiguration.class, SecurityConfig.class,
            SecurityErrorResponder.class, RedisTokenAuthenticationFilter.class, ResourceAuthorizationService.class,
            GlobalExceptionHandler.class})
    static class TestApplication {}

    @AfterEach void cleanupThreadIdentity() { UserHolder.removeUser(); }

    @Test void everyAuthenticatedRoleCanLogoutAndOnlyItsTrimmedTokenIsDeleted() throws Exception {
        for (String role : Arrays.asList("USER", "MERCHANT", "ADMIN")) {
            String token = "logout-" + role.toLowerCase();
            authenticated(token, 601L + role.length(), role);
            when(redis.delete(LOGIN_USER_KEY + token)).thenReturn(false);
            mvc.perform(post("/user/logout").header("authorization", "  " + token + "  "))
                    .andExpect(status().isOk()).andExpect(jsonPath("$.success").value(true));
            verify(redis).delete(LOGIN_USER_KEY + token);
        }
        verify(redis, never()).delete(LOGIN_USER_KEY + "  logout-user  ");
    }

    @Test void controlCharactersInHttpHeadersRemainRejectedByTheSecurityFirewall() {
        assertThrows(org.springframework.security.web.firewall.RequestRejectedException.class,
                () -> mvc.perform(post("/user/logout").header("authorization", "logout-user\t")));
        verifyNoInteractions(redis);
    }

    @Test void anonymousExpiredDisabledAndRolelessSessionsCannotLogout() throws Exception {
        mvc.perform(post("/user/logout")).andExpect(status().isUnauthorized());
        mvc.perform(post("/user/logout").header("authorization", "   ")).andExpect(status().isUnauthorized());
        when(redis.opsForHash()).thenReturn(hashes);
        when(hashes.entries(LOGIN_USER_KEY + "logout-expired")).thenReturn(Collections.emptyMap());
        mvc.perform(post("/user/logout").header("authorization", "logout-expired")).andExpect(status().isUnauthorized());
        putSession("logout-disabled", 701L);
        when(accounts.findAccountStatus(701L)).thenReturn("DISABLED");
        mvc.perform(post("/user/logout").header("authorization", "logout-disabled")).andExpect(status().isUnauthorized());
        putSession("logout-roleless", 702L);
        when(accounts.findAccountStatus(702L)).thenReturn("ACTIVE");
        when(accounts.findRoles(702L)).thenReturn(Collections.emptyList());
        mvc.perform(post("/user/logout").header("authorization", "logout-roleless")).andExpect(status().isUnauthorized());
        verify(redis, never()).delete(LOGIN_USER_KEY + "logout-expired");
        verify(redis).delete(LOGIN_USER_KEY + "logout-disabled");
        verify(redis).delete(LOGIN_USER_KEY + "logout-roleless");
    }

    @Test void successfulLogoutMakesTheSameTokenUnauthorizedOnTheNextRequest() throws Exception {
        String token = "logout-once";
        Map<Object, Object> session = putSession(token, 801L);
        when(accounts.findAccountStatus(801L)).thenReturn("ACTIVE");
        when(accounts.findRoles(801L)).thenReturn(Collections.singletonList("USER"));
        when(redis.execute(any(org.springframework.data.redis.core.script.RedisScript.class), eq(Collections.singletonList(LOGIN_USER_KEY + token)), eq("801"), anyString())).thenReturn(1L);
        when(redis.delete(LOGIN_USER_KEY + token)).thenAnswer(invocation -> { session.clear(); return true; });
        mvc.perform(post("/user/logout").header("authorization", token)).andExpect(status().isOk());
        mvc.perform(post("/user/logout").header("authorization", token)).andExpect(status().isUnauthorized());
        verify(redis).delete(LOGIN_USER_KEY + token);
    }

    @Test void redisDeleteFailureBecomesServerErrorAfterFilterOwnsRequestCleanup() throws Exception {
        String token = "logout-redis-error";
        authenticated(token, 901L, "USER");
        when(redis.delete(LOGIN_USER_KEY + token)).thenThrow(new IllegalStateException("synthetic Redis failure"));
        mvc.perform(post("/user/logout").header("authorization", token))
                .andExpect(status().is(HttpStatus.INTERNAL_SERVER_ERROR.value()))
                .andExpect(jsonPath("$.errorCode").value("INTERNAL_ERROR"));
        assertNull(UserHolder.getUser());
    }

    private void authenticated(String token, long id, String role) {
        putSession(token, id);
        when(accounts.findAccountStatus(id)).thenReturn("ACTIVE");
        when(accounts.findRoles(id)).thenReturn(Collections.singletonList(role));
        when(redis.execute(any(org.springframework.data.redis.core.script.RedisScript.class), eq(Collections.singletonList(LOGIN_USER_KEY + token)), eq(Long.toString(id)), anyString())).thenReturn(1L);
    }

    private Map<Object, Object> putSession(String token, long id) {
        when(redis.opsForHash()).thenReturn(hashes);
        Map<Object, Object> session = new HashMap<>(); session.put("id", String.valueOf(id));
        when(hashes.entries(LOGIN_USER_KEY + token)).thenReturn(session);
        return session;
    }
}
