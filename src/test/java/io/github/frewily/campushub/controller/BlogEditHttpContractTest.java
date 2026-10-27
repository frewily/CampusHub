package io.github.frewily.campushub.controller;

import io.github.frewily.campushub.config.GlobalExceptionHandler;
import io.github.frewily.campushub.config.SecurityConfig;
import io.github.frewily.campushub.dto.request.BlogEditRequest;
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
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(classes = BlogEditHttpContractTest.TestApplication.class)
@AutoConfigureMockMvc
class BlogEditHttpContractTest {
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
    void userMerchantAndAdminMayCallEditAndRequestIgnoresUnknownIdentityAndCounterFields() throws Exception {
        for (String role : Arrays.asList("USER", "MERCHANT", "ADMIN")) {
            String token = role.toLowerCase() + "-token";
            prepareSession(token, "ACTIVE", role, USER_ID);
            mockMvc.perform(put(PATH).header("authorization", token).contentType(MediaType.APPLICATION_JSON)
                            .content("{\"title\":\"edited\",\"images\":\"  images  \",\"content\":\"body\","
                                    + "\"shopId\":null,\"userId\":5,\"id\":6,\"liked\":99,\"comments\":99,"
                                    + "\"createTime\":\"2000-01-01T00:00:00\",\"extra\":true}"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.success").value(true))
                    .andExpect(jsonPath("$.data").doesNotExist());
            assertNull(UserHolder.getUser(), "identity must be cleared when the request completes");
        }
        org.mockito.ArgumentCaptor<BlogEditRequest> captor =
                org.mockito.ArgumentCaptor.forClass(BlogEditRequest.class);
        verify(blogService, times(3)).editBlog(eq(9007199254740997L), captor.capture());
        for (BlogEditRequest request : captor.getAllValues()) {
            assertEquals("edited", request.getTitle());
            assertEquals("  images  ", request.getImages());
            assertEquals("body", request.getContent());
        }
        verifyNoMoreInteractions(blogService);
    }

    @Test
    void anonymousAndInvalidSessionsAreUnauthorizedAndNeverReachService() throws Exception {
        mockMvc.perform(validPut()).andExpect(status().isUnauthorized());
        when(stringRedisTemplate.opsForHash()).thenReturn(hashOperations);
        when(hashOperations.entries(RedisConstants.LOGIN_USER_KEY + "missing-token"))
                .thenReturn(Collections.<Object, Object>emptyMap());
        mockMvc.perform(validPut().header("authorization", "missing-token"))
                .andExpect(status().isUnauthorized());

        prepareSession("disabled-token", "DISABLED", "ADMIN", USER_ID);
        mockMvc.perform(validPut().header("authorization", "disabled-token"))
                .andExpect(status().isUnauthorized());
        prepareSession("roleless-token", "ACTIVE", null, USER_ID);
        mockMvc.perform(validPut().header("authorization", "roleless-token"))
                .andExpect(status().isUnauthorized());
        prepareSession("unknown-role-token", "ACTIVE", "AUDITOR", USER_ID);
        mockMvc.perform(validPut().header("authorization", "unknown-role-token"))
                .andExpect(status().isUnauthorized());
        verifyNoInteractions(blogService);
    }

    @Test
    void editRouteRejectsNonpositiveUnparseableAndOverflowIds() throws Exception {
        prepareSession("user-token", "ACTIVE", "USER", USER_ID);
        for (String id : new String[]{"0", "-1", "not-a-number", "9223372036854775808"}) {
            mockMvc.perform(put("/blog/" + id).header("authorization", "user-token")
                            .contentType(MediaType.APPLICATION_JSON).content("{\"title\":\"t\",\"content\":\"c\"}"))
                    .andExpect(status().isBadRequest());
        }
        verifyNoInteractions(blogService);
    }

    @Test
    void validatesRequiredFieldsAndOriginalImageLengthButAcceptsNullOrMissingImages() throws Exception {
        prepareSession("user-token", "ACTIVE", "USER", USER_ID);
        String tooLong = repeat('x', 2049);
        String tooLongBlank = repeat(' ', 2049);
        for (String body : Arrays.asList("{", "null", "{}", "{\"title\":\" \",\"content\":\"c\"}",
                "{\"title\":\"t\",\"content\":\" \"}",
                "{\"title\":\"t\",\"content\":\"c\",\"images\":\"" + tooLong + "\"}",
                "{\"title\":\"t\",\"content\":\"c\",\"images\":\"" + tooLongBlank + "\"}")) {
            mockMvc.perform(validPut().header("authorization", "user-token").content(body))
                    .andExpect(status().isBadRequest());
        }

        mockMvc.perform(validPut().header("authorization", "user-token")
                        .content("{\"title\":\"t\",\"content\":\"c\"}"))
                .andExpect(status().isOk());
        mockMvc.perform(validPut().header("authorization", "user-token")
                        .content("{\"title\":\"t\",\"content\":\"c\",\"images\":null}"))
                .andExpect(status().isOk());
        org.mockito.ArgumentCaptor<BlogEditRequest> captor =
                org.mockito.ArgumentCaptor.forClass(BlogEditRequest.class);
        verify(blogService, times(2)).editBlog(eq(9007199254740997L), captor.capture());
        assertNull(captor.getAllValues().get(0).getImages());
        assertNull(captor.getAllValues().get(1).getImages());
        verifyNoMoreInteractions(blogService);
    }

    @Test
    void delegatedBusinessFailuresKeepNotFoundForbiddenValidationUnprocessableAndSanitizedServerStatuses() throws Exception {
        prepareSession("user-token", "ACTIVE", "USER", USER_ID);
        doThrow(new BusinessException(ErrorCode.NOT_FOUND)).when(blogService)
                .editBlog(eq(41L), any(BlogEditRequest.class));
        doThrow(new BusinessException(ErrorCode.AUTHORIZATION_FAILED)).when(blogService)
                .editBlog(eq(42L), any(BlogEditRequest.class));
        doThrow(new BusinessException(ErrorCode.VALIDATION_FAILED)).when(blogService)
                .editBlog(eq(43L), any(BlogEditRequest.class));
        doThrow(new BusinessException(ErrorCode.OPERATION_FAILED)).when(blogService)
                .editBlog(eq(44L), any(BlogEditRequest.class));
        doThrow(new BusinessException(ErrorCode.INTERNAL_ERROR)).when(blogService)
                .editBlog(eq(45L), any(BlogEditRequest.class));

        for (String[] scenario : new String[][]{{"41", "404", "NOT_FOUND"}, {"42", "403", "AUTHORIZATION_FAILED"},
                {"43", "400", "VALIDATION_FAILED"}, {"44", "422", "OPERATION_FAILED"},
                {"45", "500", "INTERNAL_ERROR"}}) {
            mockMvc.perform(put("/blog/" + scenario[0]).header("authorization", "user-token")
                            .contentType(MediaType.APPLICATION_JSON).content("{\"title\":\"t\",\"content\":\"c\"}"))
                    .andExpect(status().is(Integer.parseInt(scenario[1])))
                    .andExpect(jsonPath("$.errorCode").value(scenario[2]))
                    .andExpect(content().string(not(containsString("java.lang"))));
        }
        verify(blogService).editBlog(eq(41L), any(BlogEditRequest.class));
        verify(blogService).editBlog(eq(42L), any(BlogEditRequest.class));
        verify(blogService).editBlog(eq(43L), any(BlogEditRequest.class));
        verify(blogService).editBlog(eq(44L), any(BlogEditRequest.class));
        verify(blogService).editBlog(eq(45L), any(BlogEditRequest.class));
        verifyNoMoreInteractions(blogService);
    }

    private org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder validPut() {
        return put(PATH).contentType(MediaType.APPLICATION_JSON).content("{\"title\":\"t\",\"content\":\"c\"}");
    }

    private void prepareSession(String token, String accountStatus, String role, long userId) {
        when(stringRedisTemplate.opsForHash()).thenReturn(hashOperations);
        Map<Object, Object> session = new HashMap<>();
        session.put("id", Long.toString(userId));
        session.put("nickName", "editor");
        when(hashOperations.entries(RedisConstants.LOGIN_USER_KEY + token)).thenReturn(session);
        if ("ACTIVE".equals(accountStatus) && role != null) when(stringRedisTemplate.execute(any(org.springframework.data.redis.core.script.RedisScript.class), eq(Collections.singletonList(RedisConstants.LOGIN_USER_KEY + token)), eq(Long.toString(userId)), anyString())).thenReturn(1L);
        when(accountAccessMapper.findAccountStatus(userId)).thenReturn(accountStatus);
        when(accountAccessMapper.findRoles(userId)).thenReturn(role == null
                ? Collections.<String>emptyList() : Collections.singletonList(role));
    }

    private static String repeat(char character, int count) {
        char[] chars = new char[count];
        Arrays.fill(chars, character);
        return new String(chars);
    }
}
