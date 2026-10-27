package io.github.frewily.campushub.engagement.api;

import io.github.frewily.campushub.config.GlobalExceptionHandler;
import io.github.frewily.campushub.config.SecurityConfig;
import io.github.frewily.campushub.engagement.application.CheckInService;
import io.github.frewily.campushub.mapper.AccountAccessMapper;
import io.github.frewily.campushub.security.RedisTokenAuthenticationFilter;
import io.github.frewily.campushub.security.ResourceAuthorizationService;
import io.github.frewily.campushub.security.SecurityErrorResponder;
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
import org.springframework.test.web.servlet.MockMvc;

import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

import static io.github.frewily.campushub.utils.RedisConstants.LOGIN_USER_KEY;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(classes = CheckInHttpContractTest.TestApplication.class)
@AutoConfigureMockMvc
class CheckInHttpContractTest {
    @Autowired private MockMvc mvc;
    @MockBean private CheckInService service;
    @MockBean private StringRedisTemplate redis;
    @MockBean private HashOperations<String, Object, Object> hashes;
    @MockBean private AccountAccessMapper accounts;

    @SpringBootConfiguration
    @EnableAutoConfiguration
    @Import({CheckInController.class, SecurityConfig.class, SecurityErrorResponder.class,
            RedisTokenAuthenticationFilter.class, ResourceAuthorizationService.class, GlobalExceptionHandler.class})
    static class TestApplication { }

    @AfterEach
    void clearIdentity() {
        try { assertNull(UserHolder.getUser()); }
        finally { UserHolder.removeUser(); }
    }

    @Test
    void anonymousRequestsRemainUnauthorized() throws Exception {
        mvc.perform(post("/user/sign")).andExpect(status().isUnauthorized());
        mvc.perform(get("/user/sign/count")).andExpect(status().isUnauthorized());
        verifyNoInteractions(service);
    }

    @Test
    void userAndMerchantKeepTheSameRoutesAndResponses() throws Exception {
        session("user", 9007199254740993L, "ACTIVE", "USER");
        session("merchant", 32L, "ACTIVE", "MERCHANT");
        when(service.signCount(9007199254740993L)).thenReturn(3);
        when(service.signCount(32L)).thenReturn(0);
        for (String token : Arrays.asList("user", "merchant")) {
            mvc.perform(post("/user/sign").header("authorization", token).param("userId", "99"))
                    .andExpect(status().isOk()).andExpect(jsonPath("$.success").value(true))
                    .andExpect(jsonPath("$.data").doesNotExist());
            mvc.perform(get("/user/sign/count").header("authorization", token).param("userId", "99"))
                    .andExpect(status().isOk()).andExpect(jsonPath("$.success").value(true))
                    .andExpect(jsonPath("$.data").value("user".equals(token) ? 3 : 0));
        }
        verify(service).sign(9007199254740993L);
        verify(service).sign(32L);
        verify(service).signCount(9007199254740993L);
        verify(service).signCount(32L);
        verifyNoMoreInteractions(service);
    }

    @Test
    void adminCannotParticipateEvenWhenAlsoUserOrMerchant() throws Exception {
        session("admin", 33L, "ACTIVE", "ADMIN", "USER", "MERCHANT");
        mvc.perform(post("/user/sign").header("authorization", "admin"))
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.errorCode").value("AUTHORIZATION_FAILED"));
        mvc.perform(get("/user/sign/count").header("authorization", "admin"))
                .andExpect(status().isForbidden());
        verifyNoInteractions(service);
    }

    @Test
    void disabledIdentityCannotUseEitherRoute() throws Exception {
        session("disabled", 34L, "DISABLED", "USER");
        mvc.perform(post("/user/sign").header("authorization", "disabled")).andExpect(status().isUnauthorized());
        mvc.perform(get("/user/sign/count").header("authorization", "disabled")).andExpect(status().isUnauthorized());
        verifyNoInteractions(service);
    }

    @Test
    void rolelessAndUnknownRoleIdentitiesRemainUnauthorized() throws Exception {
        session("roleless", 35L, "ACTIVE");
        session("unknown", 36L, "ACTIVE", "AUDITOR");
        for (String token : Arrays.asList("roleless", "unknown")) {
            mvc.perform(post("/user/sign").header("authorization", token)).andExpect(status().isUnauthorized());
            mvc.perform(get("/user/sign/count").header("authorization", token)).andExpect(status().isUnauthorized());
        }
        verifyNoInteractions(service);
    }

    @Test
    void currentRolesAreRecheckedBetweenRequests() throws Exception {
        session("changed", 37L, "ACTIVE", "USER");
        mvc.perform(post("/user/sign").header("authorization", "changed")).andExpect(status().isOk());
        when(accounts.findRoles(37L)).thenReturn(Arrays.asList("ADMIN", "USER"));
        mvc.perform(get("/user/sign/count").header("authorization", "changed")).andExpect(status().isForbidden());
        verify(service).sign(37L);
        verifyNoMoreInteractions(service);
    }

    @Test
    void redisFailureDoesNotBecomeSuccessfulOrLeakItsMessage() throws Exception {
        session("failure", 38L, "ACTIVE", "USER");
        doThrow(new IllegalStateException("synthetic-private-store-detail")).when(service).sign(38L);
        String body = mvc.perform(post("/user/sign").header("authorization", "failure"))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.success").value(false)).andReturn().getResponse().getContentAsString();
        assertFalse(body.contains("synthetic-private-store-detail"));
    }

    private void session(String token, Long id, String status, String... roles) {
        Map<Object, Object> session = new HashMap<>();
        session.put("id", id.toString()); session.put("nickName", "synthetic");
        when(redis.opsForHash()).thenReturn(hashes);
        when(hashes.entries(LOGIN_USER_KEY + token)).thenReturn(session);
        if ("ACTIVE".equals(status) && roles.length > 0) when(redis.execute(any(org.springframework.data.redis.core.script.RedisScript.class), eq(Collections.singletonList(LOGIN_USER_KEY + token)), eq(id.toString()), anyString())).thenReturn(1L);
        when(accounts.findAccountStatus(id)).thenReturn(status);
        when(accounts.findRoles(id)).thenReturn(Arrays.asList(roles));
    }
}
