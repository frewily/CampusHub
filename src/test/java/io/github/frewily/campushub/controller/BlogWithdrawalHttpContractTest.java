package io.github.frewily.campushub.controller;

import io.github.frewily.campushub.config.GlobalExceptionHandler;
import io.github.frewily.campushub.config.SecurityConfig;
import io.github.frewily.campushub.exception.BusinessException;
import io.github.frewily.campushub.exception.ErrorCode;
import io.github.frewily.campushub.mapper.AccountAccessMapper;
import io.github.frewily.campushub.security.RedisTokenAuthenticationFilter;
import io.github.frewily.campushub.security.ResourceAuthorizationService;
import io.github.frewily.campushub.security.SecurityErrorResponder;
import io.github.frewily.campushub.service.IBlogService;
import io.github.frewily.campushub.service.BlogPublicationService;
import io.github.frewily.campushub.utils.RedisConstants;
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

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(classes = BlogWithdrawalHttpContractTest.TestApplication.class)
@AutoConfigureMockMvc
class BlogWithdrawalHttpContractTest {
    private static final String PATH = "/blog/9007199254740997";
    private static final long USER_ID = 9007199254740993L;

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private IBlogService blogService;
    @MockBean
    private BlogPublicationService blogPublicationService;
    @MockBean
    private StringRedisTemplate stringRedisTemplate;
    @MockBean
    private HashOperations<String, Object, Object> hashOperations;
    @MockBean
    private AccountAccessMapper accountAccessMapper;

    @SpringBootConfiguration
    @EnableAutoConfiguration
    @Import({BlogController.class, SecurityConfig.class, SecurityErrorResponder.class,
            RedisTokenAuthenticationFilter.class, ResourceAuthorizationService.class,
            GlobalExceptionHandler.class})
    static class TestApplication {
    }

    @AfterEach
    void clearIdentity() {
        try {
            assertNull(UserHolder.getUser(), "authentication filter must clear identity after each request");
        } finally {
            UserHolder.removeUser();
        }
    }

    @Test
    void userMerchantAndAdminCanCallDeleteAndSuccessHasNoData() throws Exception {
        for (String role : new String[]{"USER", "MERCHANT", "ADMIN"}) {
            String token = role.toLowerCase() + "-token";
            prepareSession(token, "ACTIVE", role, USER_ID);
            mockMvc.perform(delete(PATH).header("authorization", token))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.success").value(true))
                    .andExpect(jsonPath("$.data").doesNotExist());
            assertNull(UserHolder.getUser(), "identity must be cleared when the request completes");
        }

        verify(blogService, times(3)).withdrawBlog(9007199254740997L);
        verifyNoMoreInteractions(blogService);
        // The mocked service verifies the role gate and route only; ownership is covered by the service test.
    }

    @Test
    void anonymousInvalidDisabledRolelessAndUnknownRoleRequestsAreUnauthorized() throws Exception {
        mockMvc.perform(delete(PATH)).andExpect(status().isUnauthorized());

        when(stringRedisTemplate.opsForHash()).thenReturn(hashOperations);
        when(hashOperations.entries(RedisConstants.LOGIN_USER_KEY + "missing-token"))
                .thenReturn(Collections.<Object, Object>emptyMap());
        mockMvc.perform(delete(PATH).header("authorization", "missing-token"))
                .andExpect(status().isUnauthorized());

        prepareSession("disabled-token", "DISABLED", "ADMIN", USER_ID);
        mockMvc.perform(delete(PATH).header("authorization", "disabled-token"))
                .andExpect(status().isUnauthorized());
        prepareSession("roleless-token", "ACTIVE", null, USER_ID);
        mockMvc.perform(delete(PATH).header("authorization", "roleless-token"))
                .andExpect(status().isUnauthorized());
        prepareSession("unknown-role-token", "ACTIVE", "AUDITOR", USER_ID);
        mockMvc.perform(delete(PATH).header("authorization", "unknown-role-token"))
                .andExpect(status().isUnauthorized());

        verifyNoInteractions(blogService);
    }

    @Test
    void deleteRouteRejectsNonpositiveUnparseableAndOverflowIds() throws Exception {
        prepareSession("user-token", "ACTIVE", "USER", USER_ID);
        for (String id : new String[]{"0", "-1", "not-a-number", "9223372036854775808"}) {
            mockMvc.perform(delete("/blog/" + id).header("authorization", "user-token"))
                    .andExpect(status().isBadRequest());
            assertNull(UserHolder.getUser(), "identity must be cleared after invalid path parameters");
        }
        verifyNoInteractions(blogService);
    }

    @Test
    void serviceFailuresMapToNotFoundForbiddenUnprocessableAndSanitizedServerResponses() throws Exception {
        prepareSession("user-token", "ACTIVE", "USER", USER_ID);
        doThrow(new BusinessException(ErrorCode.NOT_FOUND)).when(blogService).withdrawBlog(41L);
        doThrow(new BusinessException(ErrorCode.AUTHORIZATION_FAILED)).when(blogService).withdrawBlog(42L);
        doThrow(new BusinessException(ErrorCode.OPERATION_FAILED)).when(blogService).withdrawBlog(43L);
        doThrow(new BusinessException(ErrorCode.INTERNAL_ERROR)).when(blogService).withdrawBlog(44L);

        for (String[] scenario : new String[][]{{"41", "404", "NOT_FOUND"},
                {"42", "403", "AUTHORIZATION_FAILED"}, {"43", "422", "OPERATION_FAILED"},
                {"44", "500", "INTERNAL_ERROR"}}) {
            mockMvc.perform(delete("/blog/" + scenario[0]).header("authorization", "user-token"))
                    .andExpect(status().is(Integer.parseInt(scenario[1])))
                    .andExpect(jsonPath("$.errorCode").value(scenario[2]))
                    .andExpect(content().string(not(containsString("java.lang"))));
            assertNull(UserHolder.getUser(), "identity must be cleared after delegated failures");
        }

        verify(blogService).withdrawBlog(41L);
        verify(blogService).withdrawBlog(42L);
        verify(blogService).withdrawBlog(43L);
        verify(blogService).withdrawBlog(44L);
        verifyNoMoreInteractions(blogService);
    }

    private void prepareSession(String token, String accountStatus, String role, long userId) {
        when(stringRedisTemplate.opsForHash()).thenReturn(hashOperations);
        Map<Object, Object> session = new HashMap<>();
        session.put("id", Long.toString(userId));
        session.put("nickName", "author");
        when(hashOperations.entries(RedisConstants.LOGIN_USER_KEY + token)).thenReturn(session);
        if ("ACTIVE".equals(accountStatus) && role != null) when(stringRedisTemplate.execute(any(org.springframework.data.redis.core.script.RedisScript.class), eq(Collections.singletonList(RedisConstants.LOGIN_USER_KEY + token)), eq(Long.toString(userId)), anyString())).thenReturn(1L);
        when(accountAccessMapper.findAccountStatus(userId)).thenReturn(accountStatus);
        when(accountAccessMapper.findRoles(userId)).thenReturn(role == null
                ? Collections.<String>emptyList() : Collections.singletonList(role));
    }
}
