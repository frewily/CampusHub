package io.github.frewily.campushub.controller;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.frewily.campushub.config.GlobalExceptionHandler;
import io.github.frewily.campushub.config.SecurityConfig;
import io.github.frewily.campushub.dto.response.BlogCommentHideAuditResponse;
import io.github.frewily.campushub.exception.BusinessException;
import io.github.frewily.campushub.exception.ErrorCode;
import io.github.frewily.campushub.mapper.AccountAccessMapper;
import io.github.frewily.campushub.security.RedisTokenAuthenticationFilter;
import io.github.frewily.campushub.security.ResourceAuthorizationService;
import io.github.frewily.campushub.security.SecurityErrorResponder;
import io.github.frewily.campushub.service.IBlogCommentsService;
import io.github.frewily.campushub.utils.RedisConstants;
import io.github.frewily.campushub.utils.UserHolder;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.AfterEach;
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
import org.springframework.test.web.servlet.MvcResult;

import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(classes = BlogCommentHideAuditQueryHttpContractTest.TestApplication.class)
@AutoConfigureMockMvc
class BlogCommentHideAuditQueryHttpContractTest {
    private static final String BASE = "/admin/blog-comments/";
    private static final String COMMENT_ID = "9007199254740995";
    private static final String AUDIT_ID = "9007199254741001";
    private static final String BLOG_ID = "9007199254740997";
    private static final String OPERATOR_ID = "9007199254740993";
    private static final LocalDateTime CREATED = LocalDateTime.of(2026, 10, 11, 12, 34, 56);
    private static final Set<String> RESPONSE_FIELDS = new HashSet<>(Arrays.asList(
            "id", "commentId", "blogId", "operatorUserId", "reason", "fromStatus", "toStatus", "createTime"));
    private static final ObjectMapper JSON = new ObjectMapper();

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private IBlogCommentsService commentsService;
    @MockBean
    private StringRedisTemplate stringRedisTemplate;
    @MockBean
    private HashOperations<String, Object, Object> hashOperations;
    @MockBean
    private AccountAccessMapper accountAccessMapper;

    @SpringBootConfiguration
    @EnableAutoConfiguration
    @Import({AdminBlogCommentsController.class, SecurityConfig.class, SecurityErrorResponder.class,
            RedisTokenAuthenticationFilter.class, ResourceAuthorizationService.class,
            GlobalExceptionHandler.class})
    static class TestApplication {
    }

    @AfterEach
    void clearUser() {
        try {
            assertNull(UserHolder.getUser(), "filter must clear identity after success and rejected requests");
        } finally {
            UserHolder.removeUser();
        }
    }

    @Test
    void adminGetsOnlyWhitelistedAuditFieldsWithStringIdsAndSerializedDateTime() throws Exception {
        prepareSession("admin-token", "ACTIVE", "ADMIN", 21L);
        when(commentsService.queryHideAudit(9007199254740995L)).thenReturn(audit());

        MvcResult result = mockMvc.perform(get(BASE + COMMENT_ID + "/hide-audit").header("authorization", "admin-token"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.id").value(AUDIT_ID))
                .andExpect(jsonPath("$.data.commentId").value(COMMENT_ID))
                .andExpect(jsonPath("$.data.blogId").value(BLOG_ID))
                .andExpect(jsonPath("$.data.operatorUserId").value(OPERATOR_ID))
                .andExpect(jsonPath("$.data.reason").value("policy violation"))
                .andExpect(jsonPath("$.data.fromStatus").value(0))
                .andExpect(jsonPath("$.data.toStatus").value(2))
                .andExpect(jsonPath("$.data.createTime").value("2026-10-11T12:34:56"))
                .andReturn();

        JsonNode data = JSON.readTree(result.getResponse().getContentAsString(StandardCharsets.UTF_8)).path("data");
        Set<String> actualFields = new HashSet<>();
        data.fieldNames().forEachRemaining(actualFields::add);
        assertEquals(RESPONSE_FIELDS, actualFields);
        for (String field : Arrays.asList("id", "commentId", "blogId", "operatorUserId")) {
            assertTrue(data.path(field).isTextual(), field + " must stay a decimal string");
        }
        verify(commentsService).queryHideAudit(9007199254740995L);
        verifyNoMoreInteractions(commentsService);
        assertNull(UserHolder.getUser(), "request filter must clear the thread-local user after the request");
    }

    @Test
    void onlyAdminCanReadEvenWhenOtherRoleUserMatchesRecordedOperator() throws Exception {
        mockMvc.perform(get(BASE + "1/hide-audit")).andExpect(status().isUnauthorized());

        prepareSession("user-token", "ACTIVE", "USER", 9007199254740993L);
        mockMvc.perform(get(BASE + "1/hide-audit").header("authorization", "user-token"))
                .andExpect(status().isForbidden());

        prepareSession("merchant-token", "ACTIVE", "MERCHANT", 9007199254740993L);
        mockMvc.perform(get(BASE + "1/hide-audit").header("authorization", "merchant-token"))
                .andExpect(status().isForbidden());

        verifyNoInteractions(commentsService);
    }

    @Test
    void disabledAccountAndRolelessAccountAreUnauthorized() throws Exception {
        prepareSession("disabled-token", "DISABLED", "ADMIN", 21L);
        mockMvc.perform(get(BASE + "1/hide-audit").header("authorization", "disabled-token"))
                .andExpect(status().isUnauthorized());

        prepareSession("roleless-token", "ACTIVE", null, 21L);
        when(accountAccessMapper.findRoles(21L)).thenReturn(Collections.<String>emptyList());
        mockMvc.perform(get(BASE + "1/hide-audit").header("authorization", "roleless-token"))
                .andExpect(status().isUnauthorized());

        prepareSession("unknown-role-token", "ACTIVE", "AUDITOR", 21L);
        mockMvc.perform(get(BASE + "1/hide-audit").header("authorization", "unknown-role-token"))
                .andExpect(status().isUnauthorized());

        verifyNoInteractions(commentsService);
    }

    @Test
    void invalidPathIdsAreBadRequestBeforeServiceCall() throws Exception {
        prepareSession("admin-token", "ACTIVE", "ADMIN", 21L);
        for (String id : new String[]{"0", "-1", "not-a-number", "9223372036854775808"}) {
            mockMvc.perform(get(BASE + id + "/hide-audit").header("authorization", "admin-token"))
                    .andExpect(status().isBadRequest());
        }
        verifyNoInteractions(commentsService);
    }

    @Test
    void queryErrorsMapToNotFoundAndSanitizedInternalServerError() throws Exception {
        prepareSession("admin-token", "ACTIVE", "ADMIN", 21L);
        doThrow(new BusinessException(ErrorCode.NOT_FOUND)).when(commentsService).queryHideAudit(41L);
        doThrow(new BusinessException(ErrorCode.INTERNAL_ERROR)).when(commentsService).queryHideAudit(42L);

        mockMvc.perform(get(BASE + "41/hide-audit").header("authorization", "admin-token"))
                .andExpect(status().isNotFound()).andExpect(jsonPath("$.errorCode").value("NOT_FOUND"));
        mockMvc.perform(get(BASE + "42/hide-audit").header("authorization", "admin-token"))
                .andExpect(status().isInternalServerError()).andExpect(jsonPath("$.errorCode").value("INTERNAL_ERROR"))
                .andExpect(jsonPath("$.errorMsg").value("服务器异常"));
        verify(commentsService).queryHideAudit(41L);
        verify(commentsService).queryHideAudit(42L);
        verifyNoMoreInteractions(commentsService);
    }

    private void prepareSession(String token, String accountStatus, String role, long userId) {
        when(stringRedisTemplate.opsForHash()).thenReturn(hashOperations);
        Map<Object, Object> session = new HashMap<>();
        session.put("id", Long.toString(userId));
        session.put("nickName", "moderator");
        when(hashOperations.entries(RedisConstants.LOGIN_USER_KEY + token)).thenReturn(session);
        if ("ACTIVE".equals(accountStatus) && role != null) when(stringRedisTemplate.execute(any(org.springframework.data.redis.core.script.RedisScript.class), eq(Collections.singletonList(RedisConstants.LOGIN_USER_KEY + token)), eq(Long.toString(userId)), anyString())).thenReturn(1L);
        when(accountAccessMapper.findAccountStatus(userId)).thenReturn(accountStatus);
        when(accountAccessMapper.findRoles(userId)).thenReturn(role == null
                ? Collections.<String>emptyList() : Collections.singletonList(role));
    }

    private BlogCommentHideAuditResponse audit() {
        return new BlogCommentHideAuditResponse(AUDIT_ID, COMMENT_ID, BLOG_ID, OPERATOR_ID,
                "policy violation", 0, 2, CREATED);
    }
}
