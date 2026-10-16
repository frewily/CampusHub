package io.github.frewily.campushub.controller;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.frewily.campushub.config.GlobalExceptionHandler;
import io.github.frewily.campushub.config.SecurityConfig;
import io.github.frewily.campushub.dto.response.BlogReplyHideAuditResponse;
import io.github.frewily.campushub.exception.BusinessException;
import io.github.frewily.campushub.exception.ErrorCode;
import io.github.frewily.campushub.mapper.AccountAccessMapper;
import io.github.frewily.campushub.security.RedisTokenAuthenticationFilter;
import io.github.frewily.campushub.security.ResourceAuthorizationService;
import io.github.frewily.campushub.security.SecurityErrorResponder;
import io.github.frewily.campushub.service.IBlogCommentsService;
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
import org.springframework.test.web.servlet.MvcResult;

import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(classes = BlogReplyHideAuditQueryHttpContractTest.TestApplication.class)
@AutoConfigureMockMvc
class BlogReplyHideAuditQueryHttpContractTest {
    private static final String BASE = "/admin/blog-comments/replies/";
    private static final String REPLY_ID = "9007199254740995";
    private static final String AUDIT_ID = "9007199254741001";
    private static final String BLOG_ID = "9007199254740997";
    private static final String ROOT_COMMENT_ID = "9007199254740999";
    private static final String OPERATOR_ID = "9007199254740993";
    private static final LocalDateTime CREATED = LocalDateTime.of(2026, 10, 11, 12, 34, 56);
    private static final Set<String> RESPONSE_FIELDS = new HashSet<>(Arrays.asList(
            "id", "replyId", "blogId", "rootCommentId", "operatorUserId", "reason",
            "fromStatus", "toStatus", "createTime"));

    @Autowired private MockMvc mockMvc;
    @Autowired private ObjectMapper objectMapper;
    @MockBean private IBlogCommentsService commentsService;
    @MockBean private StringRedisTemplate stringRedisTemplate;
    @MockBean private HashOperations<String, Object, Object> hashOperations;
    @MockBean private AccountAccessMapper accountAccessMapper;

    @SpringBootConfiguration
    @EnableAutoConfiguration
    @Import({AdminBlogCommentsController.class, SecurityConfig.class, SecurityErrorResponder.class,
            RedisTokenAuthenticationFilter.class, ResourceAuthorizationService.class, GlobalExceptionHandler.class})
    static class TestApplication { }

    @AfterEach
    void clearUser() {
        try {
            assertNull(UserHolder.getUser(), "authentication filter must clear UserHolder after every request");
        } finally {
            UserHolder.removeUser();
        }
    }

    @Test
    void adminReplyAuditLookupHasItsOwnEndpoint() throws Exception {
        prepareSession("admin-token", "ACTIVE", "ADMIN", 21L);
        when(commentsService.queryReplyHideAudit(9007199254740995L)).thenReturn(audit());

        MvcResult result = mockMvc.perform(get(BASE + REPLY_ID + "/hide-audit").header("authorization", "admin-token"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.id").value(AUDIT_ID))
                .andExpect(jsonPath("$.data.replyId").value(REPLY_ID))
                .andExpect(jsonPath("$.data.blogId").value(BLOG_ID))
                .andExpect(jsonPath("$.data.rootCommentId").value(ROOT_COMMENT_ID))
                .andExpect(jsonPath("$.data.operatorUserId").value(OPERATOR_ID))
                .andExpect(jsonPath("$.data.reason").value("policy violation"))
                .andExpect(jsonPath("$.data.fromStatus").value(0))
                .andExpect(jsonPath("$.data.toStatus").value(2))
                .andExpect(jsonPath("$.data.createTime").value("2026-10-11T12:34:56"))
                .andReturn();

        JsonNode data = objectMapper.readTree(result.getResponse().getContentAsString(StandardCharsets.UTF_8)).path("data");
        Set<String> actualFields = new HashSet<>();
        data.fieldNames().forEachRemaining(actualFields::add);
        assertEquals(RESPONSE_FIELDS, actualFields, "response must expose only the nine audit fields");
        for (String field : Arrays.asList("id", "replyId", "blogId", "rootCommentId", "operatorUserId")) {
            assertTrue(data.path(field).isTextual(), field + " must remain a decimal string");
        }
        assertFalse(data.has("content"));
        verify(commentsService).queryReplyHideAudit(9007199254740995L);
        verifyNoMoreInteractions(commentsService);
        assertNull(UserHolder.getUser(), "filter must clear identity after a successful request");
    }

    @Test
    void onlyAdminCanReadEvenWhenUserOrMerchantMatchesRecordedOperator() throws Exception {
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
    void disabledRolelessAndUnknownRoleAccountsAreUnauthorized() throws Exception {
        prepareSession("disabled-token", "DISABLED", "ADMIN", 21L);
        mockMvc.perform(get(BASE + "1/hide-audit").header("authorization", "disabled-token"))
                .andExpect(status().isUnauthorized());

        prepareSession("roleless-token", "ACTIVE", null, 22L);
        mockMvc.perform(get(BASE + "1/hide-audit").header("authorization", "roleless-token"))
                .andExpect(status().isUnauthorized());

        prepareSession("unknown-role-token", "ACTIVE", "AUDITOR", 23L);
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
    void lookupErrorsMapToNotFoundAndSanitizedInternalServerError() throws Exception {
        prepareSession("admin-token", "ACTIVE", "ADMIN", 21L);
        doThrow(new BusinessException(ErrorCode.NOT_FOUND)).when(commentsService).queryReplyHideAudit(41L);
        doThrow(new BusinessException(ErrorCode.INTERNAL_ERROR)).when(commentsService).queryReplyHideAudit(42L);

        mockMvc.perform(get(BASE + "41/hide-audit").header("authorization", "admin-token"))
                .andExpect(status().isNotFound()).andExpect(jsonPath("$.errorCode").value("NOT_FOUND"));
        MvcResult internal = mockMvc.perform(get(BASE + "42/hide-audit").header("authorization", "admin-token"))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.errorCode").value("INTERNAL_ERROR"))
                .andExpect(jsonPath("$.errorMsg").value("服务器异常"))
                .andReturn();
        String body = internal.getResponse().getContentAsString(StandardCharsets.UTF_8);
        assertFalse(body.contains("private audit SQL"));
        verify(commentsService).queryReplyHideAudit(41L);
        verify(commentsService).queryReplyHideAudit(42L);
        verifyNoMoreInteractions(commentsService);
        assertNull(UserHolder.getUser(), "filter must clear identity after failed business responses");
    }

    private void prepareSession(String token, String accountStatus, String role, long userId) {
        when(stringRedisTemplate.opsForHash()).thenReturn(hashOperations);
        Map<Object, Object> session = new HashMap<>();
        session.put("id", Long.toString(userId));
        session.put("nickName", "moderator");
        when(hashOperations.entries(RedisConstants.LOGIN_USER_KEY + token)).thenReturn(session);
        when(accountAccessMapper.findAccountStatus(userId)).thenReturn(accountStatus);
        when(accountAccessMapper.findRoles(userId)).thenReturn(role == null
                ? Collections.<String>emptyList() : Collections.singletonList(role));
    }

    private BlogReplyHideAuditResponse audit() {
        return new BlogReplyHideAuditResponse(AUDIT_ID, REPLY_ID, BLOG_ID, ROOT_COMMENT_ID, OPERATOR_ID,
                "policy violation", 0, 2, CREATED);
    }
}
