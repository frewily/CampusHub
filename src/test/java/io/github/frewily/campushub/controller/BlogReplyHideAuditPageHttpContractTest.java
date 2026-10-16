package io.github.frewily.campushub.controller;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.frewily.campushub.dto.request.BlogCommentHideAuditPageRequest;
import io.github.frewily.campushub.dto.response.BlogReplyHideAuditPage;
import io.github.frewily.campushub.dto.response.BlogReplyHideAuditResponse;
import io.github.frewily.campushub.config.GlobalExceptionHandler;
import io.github.frewily.campushub.config.SecurityConfig;
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
import org.springframework.test.web.servlet.ResultActions;

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

@SpringBootTest(classes = BlogReplyHideAuditPageHttpContractTest.TestApplication.class)
@AutoConfigureMockMvc
class BlogReplyHideAuditPageHttpContractTest {
    private static final String PATH = "/admin/blog-comments/replies/hide-audits/of/blog/";
    private static final long BLOG_ID = 9007199254740995L;
    private static final long ADMIN_ID = 21L;
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final Set<String> PAGE_FIELDS = new HashSet<>(Arrays.asList("items", "hasNext", "nextBeforeId"));
    private static final Set<String> AUDIT_FIELDS = new HashSet<>(Arrays.asList(
            "id", "replyId", "blogId", "rootCommentId", "operatorUserId", "reason",
            "fromStatus", "toStatus", "createTime"));

    @Autowired private MockMvc mockMvc;
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
            assertNull(UserHolder.getUser(), "authentication filter must clear identity after every request");
        } finally {
            UserHolder.removeUser();
        }
    }

    @Test
    void adminReplyAuditPagesHaveTheirOwnEndpoint() throws Exception {
        prepareSession("synthetic-admin", "ACTIVE", "ADMIN", ADMIN_ID);
        when(commentsService.listReplyHideAudits(any(Long.class), any(BlogCommentHideAuditPageRequest.class)))
                .thenReturn(page(Collections.singletonList(audit()), true, "9007199254741001"));

        MvcResult result = perform("synthetic-admin", PATH + BLOG_ID).andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.items[0].id").value("9007199254741001"))
                .andExpect(jsonPath("$.data.items[0].replyId").value("9007199254740993"))
                .andExpect(jsonPath("$.data.items[0].blogId").value("9007199254740995"))
                .andExpect(jsonPath("$.data.items[0].rootCommentId").value("9007199254740991"))
                .andExpect(jsonPath("$.data.items[0].operatorUserId").value("9007199254740997"))
                .andExpect(jsonPath("$.data.items[0].reason").value("policy violation"))
                .andExpect(jsonPath("$.data.items[0].fromStatus").value(0))
                .andExpect(jsonPath("$.data.items[0].toStatus").value(2))
                .andExpect(jsonPath("$.data.items[0].createTime").value("2026-10-11T12:34:56"))
                .andExpect(jsonPath("$.data.hasNext").value(true))
                .andExpect(jsonPath("$.data.nextBeforeId").value("9007199254741001"))
                .andReturn();

        JsonNode data = JSON.readTree(result.getResponse().getContentAsString(StandardCharsets.UTF_8)).path("data");
        assertFields(data, PAGE_FIELDS);
        JsonNode item = data.path("items").get(0);
        assertFields(item, AUDIT_FIELDS);
        for (String field : Arrays.asList("id", "replyId", "blogId", "rootCommentId", "operatorUserId")) {
            assertTrue(item.path(field).isTextual(), field + " must be a decimal string");
        }
        assertTrue(data.path("nextBeforeId").isTextual());
        verify(commentsService).listReplyHideAudits(eq(BLOG_ID), any(BlogCommentHideAuditPageRequest.class));
        verifyNoMoreInteractions(commentsService);
    }

    @Test
    void defaultAndMaximumPageParametersReachTheService() throws Exception {
        prepareSession("admin", "ACTIVE", "ADMIN", ADMIN_ID);
        when(commentsService.listReplyHideAudits(any(Long.class), any(BlogCommentHideAuditPageRequest.class)))
                .thenReturn(page(Collections.<BlogReplyHideAuditResponse>emptyList(), false, null));

        perform("admin", PATH + BLOG_ID, "unknownField", "ignored").andExpect(status().isOk());
        perform("admin", PATH + BLOG_ID, "size", "50", "beforeId", "9007199254740993")
                .andExpect(status().isOk());

        org.mockito.ArgumentCaptor<BlogCommentHideAuditPageRequest> captor =
                org.mockito.ArgumentCaptor.forClass(BlogCommentHideAuditPageRequest.class);
        verify(commentsService, times(2)).listReplyHideAudits(eq(BLOG_ID), captor.capture());
        List<BlogCommentHideAuditPageRequest> requests = captor.getAllValues();
        assertEquals(Integer.valueOf(20), requests.get(0).getSize());
        assertNull(requests.get(0).getBeforeId());
        assertEquals(Integer.valueOf(50), requests.get(1).getSize());
        assertEquals(Long.valueOf(9007199254740993L), requests.get(1).getBeforeId());
        verifyNoMoreInteractions(commentsService);
    }

    @Test
    void adminWithoutNextPageOmitsCursorAndEmptyPageIsStillSuccessful() throws Exception {
        prepareSession("admin", "ACTIVE", "ADMIN", ADMIN_ID);
        when(commentsService.listReplyHideAudits(eq(BLOG_ID), any(BlogCommentHideAuditPageRequest.class)))
                .thenReturn(page(Collections.<BlogReplyHideAuditResponse>emptyList(), false, null));

        MvcResult result = perform("admin", PATH + BLOG_ID).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items").isArray())
                .andExpect(jsonPath("$.data.items.length()").value(0))
                .andExpect(jsonPath("$.data.hasNext").value(false))
                .andExpect(jsonPath("$.data.nextBeforeId").doesNotExist())
                .andReturn();
        assertFields(JSON.readTree(result.getResponse().getContentAsString(StandardCharsets.UTF_8)).path("data"),
                new HashSet<>(Arrays.asList("items", "hasNext")));
        verify(commentsService).listReplyHideAudits(eq(BLOG_ID), any(BlogCommentHideAuditPageRequest.class));
        verifyNoMoreInteractions(commentsService);
    }

    @Test
    void onlyAdminCanReadEvenWhenUserMatchesAuditOperator() throws Exception {
        perform(null, PATH + BLOG_ID).andExpect(status().isUnauthorized());
        prepareSession("user", "ACTIVE", "USER", 9007199254740997L);
        perform("user", PATH + BLOG_ID).andExpect(status().isForbidden());
        prepareSession("merchant", "ACTIVE", "MERCHANT", 9007199254740997L);
        perform("merchant", PATH + BLOG_ID).andExpect(status().isForbidden());
        verifyNoInteractions(commentsService);
    }

    @Test
    void inactiveRolelessUnknownRoleAndUnknownSessionsAreUnauthorized() throws Exception {
        when(stringRedisTemplate.opsForHash()).thenReturn(hashOperations);
        when(hashOperations.entries(RedisConstants.LOGIN_USER_KEY + "missing"))
                .thenReturn(Collections.<Object, Object>emptyMap());
        perform("missing", PATH + BLOG_ID).andExpect(status().isUnauthorized());
        prepareSession("disabled", "DISABLED", "ADMIN", ADMIN_ID);
        perform("disabled", PATH + BLOG_ID).andExpect(status().isUnauthorized());
        prepareSession("roleless", "ACTIVE", null, ADMIN_ID);
        perform("roleless", PATH + BLOG_ID).andExpect(status().isUnauthorized());
        prepareSession("unknown-role", "ACTIVE", "AUDITOR", ADMIN_ID);
        perform("unknown-role", PATH + BLOG_ID).andExpect(status().isUnauthorized());
        verifyNoInteractions(commentsService);
    }

    @Test
    void invalidPathAndQueryParametersAreBadRequestBeforeServiceCall() throws Exception {
        prepareSession("admin", "ACTIVE", "ADMIN", ADMIN_ID);
        for (String blogId : new String[]{"0", "-1", "not-a-number", "9223372036854775808"}) {
            perform("admin", PATH + blogId).andExpect(status().isBadRequest());
        }
        for (String[] query : new String[][]{
                {"size", ""}, {"size", "0"}, {"size", "51"}, {"size", "nope"},
                {"beforeId", "0"}, {"beforeId", "-1"}, {"beforeId", "9223372036854775808"}}) {
            perform("admin", PATH + BLOG_ID, query[0], query[1]).andExpect(status().isBadRequest());
        }
        verifyNoInteractions(commentsService);
    }

    @Test
    void unmatchedRouteIsNotAnEmptyAuditPageAndServiceFailureIsGeneric500() throws Exception {
        prepareSession("admin", "ACTIVE", "ADMIN", ADMIN_ID);
        perform("admin", PATH + BLOG_ID + "/unknown").andExpect(status().isNotFound());
        doThrow(new BusinessException(ErrorCode.INTERNAL_ERROR)).when(commentsService)
                .listReplyHideAudits(eq(42L), any(BlogCommentHideAuditPageRequest.class));
        perform("admin", PATH + "42").andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.errorCode").value("INTERNAL_ERROR"))
                .andExpect(jsonPath("$.errorMsg").value("服务器异常"));
        verify(commentsService).listReplyHideAudits(eq(42L), any(BlogCommentHideAuditPageRequest.class));
        verifyNoMoreInteractions(commentsService);
    }

    private ResultActions perform(String token, String path, String... query) throws Exception {
        try {
            org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder request = get(path);
            if (token != null) request.header("authorization", token);
            for (int i = 0; i < query.length; i += 2) request.param(query[i], query[i + 1]);
            return mockMvc.perform(request);
        } finally {
            // The filter clears its request identity before MockMvc returns, including failures.
            assertNull(UserHolder.getUser(), "filter must clear request identity on every branch");
            UserHolder.removeUser();
        }
    }

    private void prepareSession(String token, String accountStatus, String role, long userId) {
        when(stringRedisTemplate.opsForHash()).thenReturn(hashOperations);
        Map<Object, Object> session = new HashMap<>();
        session.put("id", Long.toString(userId));
        when(hashOperations.entries(RedisConstants.LOGIN_USER_KEY + token)).thenReturn(session);
        when(accountAccessMapper.findAccountStatus(userId)).thenReturn(accountStatus);
        when(accountAccessMapper.findRoles(userId)).thenReturn(role == null
                ? Collections.<String>emptyList() : Collections.singletonList(role));
    }

    private BlogReplyHideAuditPage page(List<BlogReplyHideAuditResponse> items, boolean hasNext, String nextBeforeId) {
        return new BlogReplyHideAuditPage(items, hasNext, nextBeforeId);
    }

    private BlogReplyHideAuditResponse audit() {
        return new BlogReplyHideAuditResponse("9007199254741001", "9007199254740993", "9007199254740995",
                "9007199254740991", "9007199254740997", "policy violation", 0, 2,
                LocalDateTime.of(2026, 10, 11, 12, 34, 56));
    }

    private void assertFields(JsonNode node, Set<String> expected) {
        Set<String> actual = new HashSet<>();
        node.fieldNames().forEachRemaining(actual::add);
        assertEquals(expected, actual);
    }
}
