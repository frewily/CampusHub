package io.github.frewily.campushub.controller;

import io.github.frewily.campushub.config.GlobalExceptionHandler;
import io.github.frewily.campushub.config.SecurityConfig;
import io.github.frewily.campushub.dto.request.BlogCommentHideRequest;
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

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(classes = BlogCommentModerationHttpContractTest.TestApplication.class)
@AutoConfigureMockMvc
class BlogCommentModerationHttpContractTest {
    private static final String BASE = "/admin/blog-comments/";
    private static final String LARGE_ID = "9007199254740993";

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

    @Test
    void adminDirectReplyHideHasItsOwnEndpoint() throws Exception {
        prepareSession("reply-admin", "ACTIVE", "ADMIN");
        String reason = repeatedText('m', 255);
        mockMvc.perform(post(BASE + "replies/" + LARGE_ID + "/hide")
                        .header("authorization", "reply-admin")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"reason\":\"" + reason + "\",\"replyId\":4,\"status\":\"VISIBLE\","
                                + "\"operatorId\":8,\"extra\":true}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data").doesNotExist());
        assertNull(UserHolder.getUser(), "request filter must clear the thread-local user after the request");

        org.mockito.ArgumentCaptor<BlogCommentHideRequest> captor =
                org.mockito.ArgumentCaptor.forClass(BlogCommentHideRequest.class);
        verify(commentsService).hideReply(eq(9007199254740993L), captor.capture());
        assertEquals(reason, captor.getValue().getReason());
    }

    @Test
    void adminCanHideLargeIdWithMaximumReasonAndOnlyReasonIsBound() throws Exception {
        prepareSession("admin-token", "ACTIVE", "ADMIN");
        String reason = repeatedText('r', 255);

        mockMvc.perform(post(BASE + LARGE_ID + "/hide").header("authorization", "admin-token")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"reason\":\"" + reason + "\",\"commentId\":4,\"status\":\"VISIBLE\","
                                + "\"operatorId\":8,\"extra\":true}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data").doesNotExist());
        assertNull(UserHolder.getUser(), "request filter must clear the thread-local user after the request");

        org.mockito.ArgumentCaptor<BlogCommentHideRequest> captor =
                org.mockito.ArgumentCaptor.forClass(BlogCommentHideRequest.class);
        verify(commentsService).hideComment(eq(9007199254740993L), captor.capture());
        assertEquals(reason, captor.getValue().getReason());
    }

    @Test
    void anonymousAndNonAdminUsersCannotHideComments() throws Exception {
        mockMvc.perform(post(BASE + "1/hide").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"reason\":\"moderation\"}"))
                .andExpect(status().isUnauthorized());

        prepareSession("user-token", "ACTIVE", "USER");
        mockMvc.perform(post(BASE + "1/hide").header("authorization", "user-token")
                        .contentType(MediaType.APPLICATION_JSON).content("{\"reason\":\"moderation\"}"))
                .andExpect(status().isForbidden());

        prepareSession("merchant-token", "ACTIVE", "MERCHANT");
        mockMvc.perform(post(BASE + "1/hide").header("authorization", "merchant-token")
                        .contentType(MediaType.APPLICATION_JSON).content("{\"reason\":\"moderation\"}"))
                .andExpect(status().isForbidden());

        verifyNoInteractions(commentsService);
    }

    @Test
    void anonymousAndNonAdminUsersCannotHideDirectReplies() throws Exception {
        mockMvc.perform(post(BASE + "replies/1/hide").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"reason\":\"moderation\"}"))
                .andExpect(status().isUnauthorized());

        prepareSession("reply-user-token", "ACTIVE", "USER");
        mockMvc.perform(post(BASE + "replies/1/hide").header("authorization", "reply-user-token")
                        .contentType(MediaType.APPLICATION_JSON).content("{\"reason\":\"moderation\"}"))
                .andExpect(status().isForbidden());

        prepareSession("reply-merchant-token", "ACTIVE", "MERCHANT");
        mockMvc.perform(post(BASE + "replies/1/hide").header("authorization", "reply-merchant-token")
                        .contentType(MediaType.APPLICATION_JSON).content("{\"reason\":\"moderation\"}"))
                .andExpect(status().isForbidden());

        verifyNoInteractions(commentsService);
    }

    @Test
    void disabledAccountAndUnknownRoleAreRejectedAsUnauthorized() throws Exception {
        prepareSession("disabled-token", "DISABLED", "ADMIN");
        mockMvc.perform(post(BASE + "1/hide").header("authorization", "disabled-token")
                        .contentType(MediaType.APPLICATION_JSON).content("{\"reason\":\"moderation\"}"))
                .andExpect(status().isUnauthorized());

        prepareSession("disabled-reply-token", "DISABLED", "ADMIN");
        mockMvc.perform(post(BASE + "replies/1/hide").header("authorization", "disabled-reply-token")
                        .contentType(MediaType.APPLICATION_JSON).content("{\"reason\":\"moderation\"}"))
                .andExpect(status().isUnauthorized());

        prepareSession("unknown-role-token", "ACTIVE", "AUDITOR");
        mockMvc.perform(post(BASE + "1/hide").header("authorization", "unknown-role-token")
                        .contentType(MediaType.APPLICATION_JSON).content("{\"reason\":\"moderation\"}"))
                .andExpect(status().isUnauthorized());

        prepareSession("unknown-reply-role-token", "ACTIVE", "AUDITOR");
        mockMvc.perform(post(BASE + "replies/1/hide").header("authorization", "unknown-reply-role-token")
                        .contentType(MediaType.APPLICATION_JSON).content("{\"reason\":\"moderation\"}"))
                .andExpect(status().isUnauthorized());

        verifyNoInteractions(commentsService);
    }

    @Test
    void invalidIdsAndInvalidJsonBodiesAreRejectedBeforeServiceCall() throws Exception {
        prepareSession("admin-token", "ACTIVE", "ADMIN");
        for (String id : new String[]{"0", "-1", "not-a-number", "9223372036854775808"}) {
            mockMvc.perform(post(BASE + id + "/hide").header("authorization", "admin-token")
                            .contentType(MediaType.APPLICATION_JSON).content("{\"reason\":\"moderation\"}"))
                    .andExpect(status().isBadRequest());

            mockMvc.perform(post(BASE + "replies/" + id + "/hide").header("authorization", "admin-token")
                            .contentType(MediaType.APPLICATION_JSON).content("{\"reason\":\"moderation\"}"))
                    .andExpect(status().isBadRequest());
        }

        String tooLong = repeatedText('x', 256);
        for (String body : new String[]{"{", "null", "{}", "{\"reason\":null}",
                "{\"reason\":\" \"}", "{\"reason\":\"" + tooLong + "\"}"}) {
            mockMvc.perform(post(BASE + "1/hide").header("authorization", "admin-token")
                            .contentType(MediaType.APPLICATION_JSON).content(body))
                    .andExpect(status().isBadRequest());

            mockMvc.perform(post(BASE + "replies/1/hide").header("authorization", "admin-token")
                            .contentType(MediaType.APPLICATION_JSON).content(body))
                    .andExpect(status().isBadRequest());
        }
        verifyNoInteractions(commentsService);
    }

    @Test
    void hideBusinessErrorsKeepTheirHttpMappingsAndSanitizedCodes() throws Exception {
        prepareSession("admin-token", "ACTIVE", "ADMIN");
        org.mockito.Mockito.doThrow(new BusinessException(ErrorCode.NOT_FOUND))
                .when(commentsService).hideComment(eq(41L), any(BlogCommentHideRequest.class));
        org.mockito.Mockito.doThrow(new BusinessException(ErrorCode.OPERATION_FAILED))
                .when(commentsService).hideComment(eq(42L), any(BlogCommentHideRequest.class));
        org.mockito.Mockito.doThrow(new BusinessException(ErrorCode.INTERNAL_ERROR))
                .when(commentsService).hideComment(eq(43L), any(BlogCommentHideRequest.class));

        org.mockito.Mockito.doThrow(new BusinessException(ErrorCode.NOT_FOUND))
                .when(commentsService).hideReply(eq(51L), any(BlogCommentHideRequest.class));
        org.mockito.Mockito.doThrow(new BusinessException(ErrorCode.OPERATION_FAILED))
                .when(commentsService).hideReply(eq(52L), any(BlogCommentHideRequest.class));
        org.mockito.Mockito.doThrow(new BusinessException(ErrorCode.INTERNAL_ERROR))
                .when(commentsService).hideReply(eq(53L), any(BlogCommentHideRequest.class));

        for (String[] testCase : new String[][]{{"41", "404", "NOT_FOUND"},
                {"42", "422", "OPERATION_FAILED"}, {"43", "500", "INTERNAL_ERROR"}}) {
            mockMvc.perform(post(BASE + testCase[0] + "/hide").header("authorization", "admin-token")
                            .contentType(MediaType.APPLICATION_JSON).content("{\"reason\":\"moderation\"}"))
                    .andExpect(status().is(Integer.parseInt(testCase[1])))
                    .andExpect(jsonPath("$.errorCode").value(testCase[2]))
                    .andExpect(content().string(not(containsString("java.lang"))));
        }

        for (String[] testCase : new String[][]{{"51", "404", "NOT_FOUND"},
                {"52", "422", "OPERATION_FAILED"}, {"53", "500", "INTERNAL_ERROR"}}) {
            mockMvc.perform(post(BASE + "replies/" + testCase[0] + "/hide")
                            .header("authorization", "admin-token")
                            .contentType(MediaType.APPLICATION_JSON).content("{\"reason\":\"moderation\"}"))
                    .andExpect(status().is(Integer.parseInt(testCase[1])))
                    .andExpect(jsonPath("$.errorCode").value(testCase[2]))
                    .andExpect(content().string(not(containsString("java.lang"))));
        }

        verify(commentsService).hideComment(eq(41L), any(BlogCommentHideRequest.class));
        verify(commentsService).hideComment(eq(42L), any(BlogCommentHideRequest.class));
        verify(commentsService).hideComment(eq(43L), any(BlogCommentHideRequest.class));
        verify(commentsService).hideReply(eq(51L), any(BlogCommentHideRequest.class));
        verify(commentsService).hideReply(eq(52L), any(BlogCommentHideRequest.class));
        verify(commentsService).hideReply(eq(53L), any(BlogCommentHideRequest.class));
        org.mockito.Mockito.verifyNoMoreInteractions(commentsService);
    }

    private void prepareSession(String token, String accountStatus, String role) {
        when(stringRedisTemplate.opsForHash()).thenReturn(hashOperations);
        Map<Object, Object> session = new HashMap<>();
        session.put("id", "21");
        session.put("nickName", "moderator");
        when(hashOperations.entries(RedisConstants.LOGIN_USER_KEY + token)).thenReturn(session);
        when(accountAccessMapper.findAccountStatus(21L)).thenReturn(accountStatus);
        when(accountAccessMapper.findRoles(21L)).thenReturn(Collections.singletonList(role));
    }

    private static String repeatedText(char value, int length) {
        StringBuilder text = new StringBuilder(length);
        for (int i = 0; i < length; i++) text.append(value);
        return text.toString();
    }
}
