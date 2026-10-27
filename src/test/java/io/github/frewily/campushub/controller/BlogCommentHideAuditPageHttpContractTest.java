package io.github.frewily.campushub.controller;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.frewily.campushub.config.GlobalExceptionHandler;
import io.github.frewily.campushub.config.SecurityConfig;
import io.github.frewily.campushub.dto.request.BlogCommentHideAuditPageRequest;
import io.github.frewily.campushub.dto.response.BlogCommentHideAuditPage;
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
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(classes = BlogCommentHideAuditPageHttpContractTest.TestApplication.class)
@AutoConfigureMockMvc
class BlogCommentHideAuditPageHttpContractTest {
    private static final String PATH = "/admin/blog-comments/hide-audits/of/blog/";
    private static final long BLOG_ID = 9007199254740997L;
    private static final long ADMIN_ID = 21L;
    private static final Set<String> AUDIT_FIELDS = new HashSet<>(Arrays.asList(
            "id", "commentId", "blogId", "operatorUserId", "reason", "fromStatus", "toStatus", "createTime"));
    private static final Set<String> PAGE_FIELDS = new HashSet<>(Arrays.asList("items", "hasNext", "nextBeforeId"));
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
            assertNull(UserHolder.getUser(), "authentication filter must clear identity after every request");
        } finally {
            UserHolder.removeUser();
        }
    }

    @Test
    void adminReceivesWhitelistedStoredAuditPageWithStringIdsAndTimestamp() throws Exception {
        prepareSession("admin-token", "ACTIVE", "ADMIN", ADMIN_ID);
        when(commentsService.listHideAudits(any(Long.class), any(BlogCommentHideAuditPageRequest.class)))
                .thenReturn(page(Collections.singletonList(audit()), true, "9007199254741001"));

        MvcResult result = mockMvc.perform(get(PATH + BLOG_ID).header("authorization", "admin-token"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.items[0].id").value("9007199254741001"))
                .andExpect(jsonPath("$.data.items[0].commentId").value("9007199254740995"))
                .andExpect(jsonPath("$.data.items[0].blogId").value("9007199254740997"))
                .andExpect(jsonPath("$.data.items[0].operatorUserId").value("9007199254740993"))
                .andExpect(jsonPath("$.data.items[0].reason").value("policy violation"))
                .andExpect(jsonPath("$.data.items[0].fromStatus").value(0))
                .andExpect(jsonPath("$.data.items[0].toStatus").value(2))
                .andExpect(jsonPath("$.data.items[0].createTime").value("2026-10-11T12:34:56"))
                .andExpect(jsonPath("$.data.hasNext").value(true))
                .andExpect(jsonPath("$.data.nextBeforeId").value("9007199254741001"))
                .andReturn();

        JsonNode data = JSON.readTree(result.getResponse().getContentAsString(StandardCharsets.UTF_8)).path("data");
        Set<String> pageFields = new HashSet<>();
        data.fieldNames().forEachRemaining(pageFields::add);
        assertEquals(PAGE_FIELDS, pageFields);
        JsonNode item = data.path("items").get(0);
        Set<String> auditFields = new HashSet<>();
        item.fieldNames().forEachRemaining(auditFields::add);
        assertEquals(AUDIT_FIELDS, auditFields);
        for (String field : Arrays.asList("id", "commentId", "blogId", "operatorUserId")) {
            assertTrue(item.path(field).isTextual(), field + " must be a decimal string");
        }
        assertTrue(data.path("nextBeforeId").isTextual(), "audit cursor must be a decimal string");
        verify(commentsService).listHideAudits(eq(BLOG_ID), any(BlogCommentHideAuditPageRequest.class));
        verifyNoMoreInteractions(commentsService);
    }

    @Test
    void defaultAndBoundedQueryParametersAndUnknownQueryFieldReachServiceAsExpected() throws Exception {
        prepareSession("admin-token", "ACTIVE", "ADMIN", ADMIN_ID);
        when(commentsService.listHideAudits(any(Long.class), any(BlogCommentHideAuditPageRequest.class)))
                .thenReturn(page(Collections.<BlogCommentHideAuditResponse>emptyList(), false, null));

        mockMvc.perform(get(PATH + BLOG_ID).header("authorization", "admin-token").param("unknownField", "ignored"))
                .andExpect(status().isOk());
        mockMvc.perform(get(PATH + BLOG_ID).header("authorization", "admin-token")
                        .param("size", "50").param("beforeId", "9007199254740993"))
                .andExpect(status().isOk());

        org.mockito.ArgumentCaptor<BlogCommentHideAuditPageRequest> captor =
                org.mockito.ArgumentCaptor.forClass(BlogCommentHideAuditPageRequest.class);
        verify(commentsService, times(2)).listHideAudits(eq(BLOG_ID), captor.capture());
        List<BlogCommentHideAuditPageRequest> requests = captor.getAllValues();
        assertEquals(Integer.valueOf(20), requests.get(0).getSize());
        assertNull(requests.get(0).getBeforeId());
        assertEquals(Integer.valueOf(50), requests.get(1).getSize());
        assertEquals(Long.valueOf(9007199254740993L), requests.get(1).getBeforeId());
        verifyNoMoreInteractions(commentsService);
    }

    @Test
    void onlyAdminCanReadIncludingWhenUserMatchesAnAuditOperator() throws Exception {
        mockMvc.perform(get(PATH + BLOG_ID)).andExpect(status().isUnauthorized());

        prepareSession("user-token", "ACTIVE", "USER", 9007199254740993L);
        mockMvc.perform(get(PATH + BLOG_ID).header("authorization", "user-token"))
                .andExpect(status().isForbidden());

        prepareSession("merchant-token", "ACTIVE", "MERCHANT", 9007199254740993L);
        mockMvc.perform(get(PATH + BLOG_ID).header("authorization", "merchant-token"))
                .andExpect(status().isForbidden());
        verifyNoInteractions(commentsService);
    }

    @Test
    void disabledRolelessUnknownRoleAndUnknownSessionAreUnauthorized() throws Exception {
        when(stringRedisTemplate.opsForHash()).thenReturn(hashOperations);
        when(hashOperations.entries(RedisConstants.LOGIN_USER_KEY + "missing-token"))
                .thenReturn(Collections.<Object, Object>emptyMap());
        mockMvc.perform(get(PATH + BLOG_ID).header("authorization", "missing-token"))
                .andExpect(status().isUnauthorized());

        prepareSession("disabled-token", "DISABLED", "ADMIN", ADMIN_ID);
        mockMvc.perform(get(PATH + BLOG_ID).header("authorization", "disabled-token"))
                .andExpect(status().isUnauthorized());

        prepareSession("roleless-token", "ACTIVE", null, ADMIN_ID);
        mockMvc.perform(get(PATH + BLOG_ID).header("authorization", "roleless-token"))
                .andExpect(status().isUnauthorized());

        prepareSession("unknown-role-token", "ACTIVE", "AUDITOR", ADMIN_ID);
        mockMvc.perform(get(PATH + BLOG_ID).header("authorization", "unknown-role-token"))
                .andExpect(status().isUnauthorized());
        verifyNoInteractions(commentsService);
    }

    @Test
    void invalidBlogPathAndQueryValuesAreBadRequestBeforeServiceCall() throws Exception {
        prepareSession("admin-token", "ACTIVE", "ADMIN", ADMIN_ID);
        for (String blogId : new String[]{"0", "-1", "not-a-number", "9223372036854775808"}) {
            mockMvc.perform(get(PATH + blogId).header("authorization", "admin-token"))
                    .andExpect(status().isBadRequest());
        }
        for (Map<String, String> query : Arrays.asList(
                query("beforeId", "0"), query("beforeId", "-1"), query("beforeId", "9223372036854775808"),
                query("size", "0"), query("size", "51"), query("size", "not-a-number"))) {
            mockMvc.perform(get(PATH + BLOG_ID).header("authorization", "admin-token")
                            .param(query.keySet().iterator().next(), query.values().iterator().next()))
                    .andExpect(status().isBadRequest());
        }
        verifyNoInteractions(commentsService);
    }

    @Test
    void emptyPageIsSuccessfulAndDatabaseErrorIsSanitized() throws Exception {
        prepareSession("admin-token", "ACTIVE", "ADMIN", ADMIN_ID);
        when(commentsService.listHideAudits(eq(BLOG_ID), any(BlogCommentHideAuditPageRequest.class)))
                .thenReturn(page(Collections.<BlogCommentHideAuditResponse>emptyList(), false, null));
        doThrow(new BusinessException(ErrorCode.INTERNAL_ERROR)).when(commentsService)
                .listHideAudits(eq(42L), any(BlogCommentHideAuditPageRequest.class));

        mockMvc.perform(get(PATH + BLOG_ID).header("authorization", "admin-token"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items").isArray())
                .andExpect(jsonPath("$.data.items.length()").value(0))
                .andExpect(jsonPath("$.data.hasNext").value(false))
                .andExpect(jsonPath("$.data.nextBeforeId").doesNotExist());
        mockMvc.perform(get(PATH + "42").header("authorization", "admin-token"))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.errorCode").value("INTERNAL_ERROR"))
                .andExpect(jsonPath("$.errorMsg").value("服务器异常"));
        verify(commentsService, times(2)).listHideAudits(any(Long.class), any(BlogCommentHideAuditPageRequest.class));
        verifyNoMoreInteractions(commentsService);
    }

    private Map<String, String> query(String key, String value) {
        Map<String, String> result = new HashMap<>();
        result.put(key, value);
        return result;
    }

    private BlogCommentHideAuditPage page(List<BlogCommentHideAuditResponse> items, boolean hasNext, String nextBeforeId) {
        return new BlogCommentHideAuditPage(items, hasNext, nextBeforeId);
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
        return new BlogCommentHideAuditResponse("9007199254741001", "9007199254740995", "9007199254740997",
                "9007199254740993", "policy violation", 0, 2,
                LocalDateTime.of(2026, 10, 11, 12, 34, 56));
    }
}
