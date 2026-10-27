package io.github.frewily.campushub.config;

import io.github.frewily.campushub.controller.UserController;
import io.github.frewily.campushub.identity.api.UserProfileController;
import io.github.frewily.campushub.identity.api.LogoutController;
import io.github.frewily.campushub.identity.api.LoginCodeController;
import io.github.frewily.campushub.identity.application.SessionLogoutService;
import io.github.frewily.campushub.identity.infrastructure.RedisSessionTerminator;
import io.github.frewily.campushub.identity.application.UserProfileQueryService;
import io.github.frewily.campushub.identity.application.LoginCodeService;
import io.github.frewily.campushub.engagement.api.CheckInController;
import io.github.frewily.campushub.engagement.application.CheckInService;
import io.github.frewily.campushub.promotion.api.VoucherQueryController;
import io.github.frewily.campushub.promotion.application.VoucherQueryService;
import io.github.frewily.campushub.controller.ShopController;
import io.github.frewily.campushub.controller.VoucherController;
import io.github.frewily.campushub.controller.BlogController;
import io.github.frewily.campushub.controller.BlogCommentsController;
import io.github.frewily.campushub.dto.Result;
import io.github.frewily.campushub.dto.UserDTO;
import io.github.frewily.campushub.dto.response.UserInfoResponse;
import io.github.frewily.campushub.mapper.AccountAccessMapper;
import io.github.frewily.campushub.security.RedisTokenAuthenticationFilter;
import io.github.frewily.campushub.security.ResourceAuthorizationService;
import io.github.frewily.campushub.security.SecurityErrorResponder;
import io.github.frewily.campushub.service.IUserService;
import io.github.frewily.campushub.service.IShopService;
import io.github.frewily.campushub.service.IVoucherService;
import io.github.frewily.campushub.service.IBlogService;
import io.github.frewily.campushub.service.BlogPublicationService;
import io.github.frewily.campushub.service.IBlogCommentsService;
import io.github.frewily.campushub.dto.response.BlogCommentItem;
import io.github.frewily.campushub.dto.response.BlogCommentPage;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.data.redis.core.HashOperations;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.http.MediaType;

import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;
import java.util.Collections;
import java.time.LocalDate;

import static io.github.frewily.campushub.utils.RedisConstants.LOGIN_USER_KEY;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.times;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(classes = SecurityHttpContractTest.TestApplication.class)
@AutoConfigureMockMvc
class SecurityHttpContractTest {

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private IUserService userService;
    @MockBean
    private CheckInService checkInService;
    @MockBean
    private UserProfileQueryService userProfileQueryService;
    @MockBean
    private LoginCodeService loginCodeService;
    @MockBean
    private IShopService shopService;
    @MockBean
    private IVoucherService voucherService;
    @MockBean
    private VoucherQueryService voucherQueryService;
    @MockBean
    private IBlogService blogService;
    @MockBean
    private BlogPublicationService blogPublicationService;
    @MockBean
    private IBlogCommentsService blogCommentsService;
    @MockBean
    private StringRedisTemplate stringRedisTemplate;
    @MockBean
    private HashOperations<String, Object, Object> hashOperations;
    @MockBean
    private AccountAccessMapper accountAccessMapper;

    @SpringBootConfiguration
    @EnableAutoConfiguration
    @Import({
            UserController.class,
            LoginCodeController.class,
            UserProfileController.class,
            LogoutController.class,
            RedisSessionTerminator.class,
            SessionLogoutService.class,
            CheckInController.class,
            ShopController.class,
            VoucherController.class,
            VoucherQueryController.class,
            BlogController.class,
            BlogCommentsController.class,
            GlobalExceptionHandler.class,
            SecurityConfig.class,
            SecurityErrorResponder.class,
            RedisTokenAuthenticationFilter.class,
            ResourceAuthorizationService.class
    })
    static class TestApplication {
    }

    @Test
    void anonymousProtectedRequestShouldReturnTypedUnauthorizedResponse() throws Exception {
        mockMvc.perform(get("/user/me"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.errorCode").value("AUTHENTICATION_FAILED"))
                .andExpect(jsonPath("$.errorMsg").value("请先登录"));
    }

    @Test
    void errorDispatchRemainsUnauthorizedUnderExistingSecurityPolicy() throws Exception {
        mockMvc.perform(get("/error").header("authorization", "synthetic-error-dispatch")
                        .with(request -> {
                            request.setDispatcherType(javax.servlet.DispatcherType.ERROR);
                            request.setAttribute(javax.servlet.RequestDispatcher.ERROR_REQUEST_URI, "/user/me");
                            return request;
                        }))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.errorCode").value("AUTHENTICATION_FAILED"));
        verifyNoInteractions(stringRedisTemplate, accountAccessMapper);
    }

    @Test
    void profileReadsAllowAllThreeRolesAndKeepLargeIdentifiersAsJsonNumbers() throws Exception {
        long targetId = 9007199254740993L;
        prepareSession("profile-user", 71L, "USER");
        prepareSession("profile-merchant", 72L, "MERCHANT");
        prepareSession("profile-admin", 73L, "ADMIN");
        UserDTO user = new UserDTO();
        user.setId(targetId);
        user.setNickName("target");
        user.setIcon("/avatar.png");
        when(userProfileQueryService.queryUserById(targetId)).thenReturn(user);
        UserInfoResponse info = new UserInfoResponse();
        info.setUserId(targetId);
        info.setCity("Synthetic City");
        info.setIntroduce("Synthetic bio");
        info.setFans(2);
        info.setFollowee(3);
        info.setGender(false);
        info.setBirthday(LocalDate.of(2001, 2, 3));
        info.setCredits(4);
        info.setLevel(true);
        when(userProfileQueryService.info(targetId)).thenReturn(info);

        for (String token : Arrays.asList("profile-user", "profile-merchant", "profile-admin")) {
            mockMvc.perform(get("/user/{id}", targetId).header("authorization", token))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.id").value(targetId))
                    .andExpect(jsonPath("$.data.id").isNumber())
                    .andExpect(jsonPath("$.data.nickName").value("target"))
                    .andExpect(jsonPath("$.data.icon").value("/avatar.png"))
                    .andExpect(jsonPath("$.data.phone").doesNotExist())
                    .andExpect(jsonPath("$.data.password").doesNotExist())
                    .andExpect(jsonPath("$.data.status").doesNotExist())
                    .andExpect(jsonPath("$.data.createTime").doesNotExist())
                    .andExpect(jsonPath("$.data.updateTime").doesNotExist());
            mockMvc.perform(get("/user/info/{id}", targetId).header("authorization", token))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.userId").value(targetId))
                    .andExpect(jsonPath("$.data.userId").isNumber())
                    .andExpect(jsonPath("$.data.city").value("Synthetic City"))
                    .andExpect(jsonPath("$.data.introduce").value("Synthetic bio"))
                    .andExpect(jsonPath("$.data.fans").value(2))
                    .andExpect(jsonPath("$.data.followee").value(3))
                    .andExpect(jsonPath("$.data.gender").value(false))
                    .andExpect(jsonPath("$.data.birthday").value("2001-02-03"))
                    .andExpect(jsonPath("$.data.credits").value(4))
                    .andExpect(jsonPath("$.data.level").value(true))
                    .andExpect(jsonPath("$.data.password").doesNotExist())
                    .andExpect(jsonPath("$.data.phone").doesNotExist())
                    .andExpect(jsonPath("$.data.status").doesNotExist())
                    .andExpect(jsonPath("$.data.createTime").doesNotExist())
                    .andExpect(jsonPath("$.data.updateTime").doesNotExist());
        }
        verify(userProfileQueryService, times(3)).queryUserById(targetId);
        verify(userProfileQueryService, times(3)).info(targetId);
    }

    @Test
    void currentProfileComesOnlyFromSessionAndDoesNotQueryProfiles() throws Exception {
        Map<Object, Object> session = new HashMap<>();
        session.put("id", "81");
        session.put("nickName", "session-user");
        session.put("icon", "/session.png");
        when(stringRedisTemplate.opsForHash()).thenReturn(hashOperations);
        when(hashOperations.entries(LOGIN_USER_KEY + "profile-me-token")).thenReturn(session);
        when(stringRedisTemplate.execute(any(org.springframework.data.redis.core.script.RedisScript.class), eq(Collections.singletonList(LOGIN_USER_KEY + "profile-me-token")), eq("81"), anyString())).thenReturn(1L);
        when(accountAccessMapper.findAccountStatus(81L)).thenReturn("ACTIVE");
        when(accountAccessMapper.findRoles(81L)).thenReturn(Collections.singletonList("USER"));

        mockMvc.perform(get("/user/me").header("authorization", "profile-me-token"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.id").value(81))
                .andExpect(jsonPath("$.data.nickName").value("session-user"))
                .andExpect(jsonPath("$.data.icon").value("/session.png"));
        verifyNoInteractions(userProfileQueryService);
    }

    @Test
    void anonymousExpiredDisabledAndRolelessProfileReadsAreUnauthorized() throws Exception {
        mockMvc.perform(get("/user/91")).andExpect(status().isUnauthorized());

        String expiredToken = "profile-expired-token";
        when(stringRedisTemplate.opsForHash()).thenReturn(hashOperations);
        when(hashOperations.entries(LOGIN_USER_KEY + expiredToken)).thenReturn(Collections.emptyMap());
        mockMvc.perform(get("/user/91").header("authorization", expiredToken))
                .andExpect(status().isUnauthorized());

        Map<Object, Object> disabled = new HashMap<>();
        disabled.put("id", "92");
        disabled.put("nickName", "disabled");
        when(hashOperations.entries(LOGIN_USER_KEY + "profile-disabled-token")).thenReturn(disabled);
        when(accountAccessMapper.findAccountStatus(92L)).thenReturn("DISABLED");
        when(accountAccessMapper.findRoles(92L)).thenReturn(Collections.singletonList("USER"));
        mockMvc.perform(get("/user/info/91").header("authorization", "profile-disabled-token"))
                .andExpect(status().isUnauthorized());

        Map<Object, Object> roleless = new HashMap<>();
        roleless.put("id", "93");
        roleless.put("nickName", "roleless");
        when(hashOperations.entries(LOGIN_USER_KEY + "profile-roleless-token")).thenReturn(roleless);
        when(accountAccessMapper.findAccountStatus(93L)).thenReturn("ACTIVE");
        when(accountAccessMapper.findRoles(93L)).thenReturn(Collections.emptyList());
        mockMvc.perform(get("/user/91").header("authorization", "profile-roleless-token"))
                .andExpect(status().isUnauthorized());
        verifyNoInteractions(userProfileQueryService);
    }

    @Test
    void profileIdsMustBePositiveLongsAndMissingRowsRemainSuccessful() throws Exception {
        prepareSession("profile-validation-token", 94L, "USER");
        for (String path : Arrays.asList("/user/0", "/user/-1", "/user/info/0", "/user/info/-1")) {
            mockMvc.perform(get(path).header("authorization", "profile-validation-token"))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.errorCode").value("VALIDATION_FAILED"))
                    .andExpect(jsonPath("$.errorMsg").value("用户ID必须为正数"));
        }
        for (String path : Arrays.asList("/user/9223372036854775808", "/user/info/9223372036854775808")) {
            mockMvc.perform(get(path).header("authorization", "profile-validation-token"))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.errorCode").value("VALIDATION_FAILED"));
        }
        when(userProfileQueryService.queryUserById(95L)).thenReturn(null);
        when(userProfileQueryService.info(96L)).thenReturn(null);
        mockMvc.perform(get("/user/95").header("authorization", "profile-validation-token"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data").doesNotExist());
        mockMvc.perform(get("/user/info/96").header("authorization", "profile-validation-token"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data").doesNotExist());
        verify(userProfileQueryService).queryUserById(95L);
        verify(userProfileQueryService).info(96L);
    }

    @Test
    void profileReadDoesNotApplyTargetOwnershipOrTargetAccountStatusRules() throws Exception {
        prepareSession("profile-cross-user-token", 97L, "USER");
        UserDTO disabledTarget = new UserDTO();
        disabledTarget.setId(98L);
        disabledTarget.setNickName("disabled-target");
        when(userProfileQueryService.queryUserById(98L)).thenReturn(disabledTarget);
        mockMvc.perform(get("/user/98").header("authorization", "profile-cross-user-token"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.id").value(98))
                .andExpect(jsonPath("$.data.nickName").value("disabled-target"));
        verify(userProfileQueryService).queryUserById(98L);
        verify(accountAccessMapper, never()).findAccountStatus(98L);
        verify(accountAccessMapper, never()).findRoles(98L);
    }

    @Test
    void profilePersistenceFailureUsesTheGenericServerError() throws Exception {
        prepareSession("profile-failure-token", 99L, "USER");
        when(userProfileQueryService.info(100L)).thenThrow(new IllegalStateException("synthetic SQL detail"));
        mockMvc.perform(get("/user/info/100").header("authorization", "profile-failure-token"))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.errorCode").value("INTERNAL_ERROR"))
                .andExpect(jsonPath("$.errorMsg").value("服务器异常"));
    }

    @Test
    void anonymousBlogPublishShouldRemainUnauthorizedWithoutCallingBusinessService() throws Exception {
        mockMvc.perform(post("/blog").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"title\":\"Lunch\",\"images\":\"/imgs/lunch.jpg\",\"content\":\"Good\"}"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.errorCode").value("AUTHENTICATION_FAILED"));

        verifyNoInteractions(blogService);
    }

    @Test
    void anonymousCommentCreationIsUnauthorized() throws Exception {
        mockMvc.perform(post("/blog-comments").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"blogId\":1,\"content\":\"Nice\"}"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.errorCode").value("AUTHENTICATION_FAILED"));
        verifyNoInteractions(blogCommentsService);
    }

    @Test
    void userMerchantAndAdminCanCreateComments() throws Exception {
        prepareSession("comment-user-token", 31L, "USER");
        prepareSession("comment-merchant-token", 32L, "MERCHANT");
        prepareSession("comment-admin-token", 33L, "ADMIN");
        when(blogCommentsService.createComment(any())).thenReturn(
                new BlogCommentItem("41", "51", "31", "Nice", null));

        for (String token : Arrays.asList("comment-user-token", "comment-merchant-token", "comment-admin-token")) {
            mockMvc.perform(post("/blog-comments").header("authorization", token)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"blogId\":51,\"content\":\"Nice\"}"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.id").value("41"));
        }
        verify(blogCommentsService, times(3)).createComment(any());
    }

    @Test
    void disabledAccountCannotCreateComment() throws Exception {
        String token = "disabled-comment-token";
        Map<Object, Object> session = new HashMap<>();
        session.put("id", "34");
        session.put("nickName", "disabled-user");
        when(stringRedisTemplate.opsForHash()).thenReturn(hashOperations);
        when(hashOperations.entries(LOGIN_USER_KEY + token)).thenReturn(session);
        when(accountAccessMapper.findAccountStatus(34L)).thenReturn("DISABLED");

        mockMvc.perform(post("/blog-comments").header("authorization", token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"blogId\":51,\"content\":\"Nice\"}"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.errorCode").value("AUTHENTICATION_FAILED"));
        verifyNoInteractions(blogCommentsService);
    }

    @Test
    void anonymousReplyCreateAndReadAreUnauthorized() throws Exception {
        mockMvc.perform(post("/blog-comments/41/replies").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"content\":\"Nice\"}"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.errorCode").value("AUTHENTICATION_FAILED"));
        mockMvc.perform(get("/blog-comments/41/replies"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.errorCode").value("AUTHENTICATION_FAILED"));
        verifyNoInteractions(blogCommentsService);
    }

    @Test
    void disabledAccountCannotCreateOrReadReplies() throws Exception {
        String token = "disabled-reply-token";
        Map<Object, Object> session = new HashMap<>();
        session.put("id", "35");
        session.put("nickName", "disabled-user");
        when(stringRedisTemplate.opsForHash()).thenReturn(hashOperations);
        when(hashOperations.entries(LOGIN_USER_KEY + token)).thenReturn(session);
        when(accountAccessMapper.findAccountStatus(35L)).thenReturn("DISABLED");

        mockMvc.perform(post("/blog-comments/41/replies").header("authorization", token)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"content\":\"Nice\"}"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.errorCode").value("AUTHENTICATION_FAILED"));
        mockMvc.perform(get("/blog-comments/41/replies").header("authorization", token))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.errorCode").value("AUTHENTICATION_FAILED"));
        verifyNoInteractions(blogCommentsService);
    }

    @Test
    void userMerchantAndAdminCanCreateAndReadReplies() throws Exception {
        prepareSession("reply-user-token", 36L, "USER");
        prepareSession("reply-merchant-token", 37L, "MERCHANT");
        prepareSession("reply-admin-token", 38L, "ADMIN");
        when(blogCommentsService.createReply(any(), any())).thenReturn(
                new BlogCommentItem("41", "51", "36", "Nice", null));
        when(blogCommentsService.listReplies(any(), any())).thenReturn(
                new BlogCommentPage(Collections.singletonList(
                        new BlogCommentItem("41", "51", "36", "Nice", null)), false, null));

        for (String token : Arrays.asList("reply-user-token", "reply-merchant-token", "reply-admin-token")) {
            mockMvc.perform(post("/blog-comments/40/replies").header("authorization", token)
                            .contentType(MediaType.APPLICATION_JSON).content("{\"content\":\"Nice\"}"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.id").value("41"));
            mockMvc.perform(get("/blog-comments/40/replies").header("authorization", token))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.items[0].id").value("41"));
        }
        verify(blogCommentsService, times(3)).createReply(any(), any());
        verify(blogCommentsService, times(3)).listReplies(any(), any());
    }

    @Test
    void userMerchantAndAdminCanWithdrawTheirReplies() throws Exception {
        prepareSession("withdraw-user-token", 46L, "USER");
        prepareSession("withdraw-merchant-token", 47L, "MERCHANT");
        prepareSession("withdraw-admin-token", 48L, "ADMIN");

        for (String token : Arrays.asList("withdraw-user-token", "withdraw-merchant-token", "withdraw-admin-token")) {
            mockMvc.perform(delete("/blog-comments/replies/51").header("authorization", token))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data").doesNotExist());
        }

        verify(blogCommentsService, times(3)).withdrawReply(51L);
    }

    @Test
    void anonymousDisabledAndUnknownRoleCannotWithdrawReplies() throws Exception {
        mockMvc.perform(delete("/blog-comments/replies/51"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.errorCode").value("AUTHENTICATION_FAILED"));

        String disabledToken = "withdraw-disabled-token";
        Map<Object, Object> disabledSession = new HashMap<>();
        disabledSession.put("id", "49");
        disabledSession.put("nickName", "disabled-user");
        when(stringRedisTemplate.opsForHash()).thenReturn(hashOperations);
        when(hashOperations.entries(LOGIN_USER_KEY + disabledToken)).thenReturn(disabledSession);
        when(accountAccessMapper.findAccountStatus(49L)).thenReturn("DISABLED");
        when(accountAccessMapper.findRoles(49L)).thenReturn(Collections.singletonList("USER"));
        mockMvc.perform(delete("/blog-comments/replies/51").header("authorization", disabledToken))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.errorCode").value("AUTHENTICATION_FAILED"));

        prepareSession("withdraw-unknown-role-token", 50L, "AUDITOR");
        mockMvc.perform(delete("/blog-comments/replies/51").header("authorization", "withdraw-unknown-role-token"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.errorCode").value("AUTHENTICATION_FAILED"));

        verifyNoInteractions(blogCommentsService);
    }

    @Test
    void userMerchantAndAdminCanAccessFirstLevelCommentWithdrawal() throws Exception {
        prepareSession("withdraw-comment-user-token", 56L, "USER");
        prepareSession("withdraw-comment-merchant-token", 57L, "MERCHANT");
        prepareSession("withdraw-comment-admin-token", 58L, "ADMIN");

        for (String token : Arrays.asList("withdraw-comment-user-token", "withdraw-comment-merchant-token",
                "withdraw-comment-admin-token")) {
            mockMvc.perform(delete("/blog-comments/61").header("authorization", token))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.success").value(true))
                    .andExpect(jsonPath("$.data").doesNotExist());
        }

        verify(blogCommentsService, times(3)).withdrawComment(61L);
    }

    @Test
    void anonymousDisabledAndUnknownRoleCannotWithdrawFirstLevelComments() throws Exception {
        mockMvc.perform(delete("/blog-comments/61"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.errorCode").value("AUTHENTICATION_FAILED"));

        String disabledToken = "withdraw-comment-disabled-token";
        Map<Object, Object> disabledSession = new HashMap<>();
        disabledSession.put("id", "59");
        disabledSession.put("nickName", "disabled-user");
        when(stringRedisTemplate.opsForHash()).thenReturn(hashOperations);
        when(hashOperations.entries(LOGIN_USER_KEY + disabledToken)).thenReturn(disabledSession);
        when(accountAccessMapper.findAccountStatus(59L)).thenReturn("DISABLED");
        when(accountAccessMapper.findRoles(59L)).thenReturn(Collections.singletonList("USER"));
        mockMvc.perform(delete("/blog-comments/61").header("authorization", disabledToken))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.errorCode").value("AUTHENTICATION_FAILED"));

        prepareSession("withdraw-comment-unknown-role-token", 60L, "AUDITOR");
        mockMvc.perform(delete("/blog-comments/61").header("authorization", "withdraw-comment-unknown-role-token"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.errorCode").value("AUTHENTICATION_FAILED"));

        verifyNoInteractions(blogCommentsService);
    }

    @Test
    void activeUserMerchantAndAdminCanPublishBlogWithoutShop() throws Exception {
        prepareSession("blog-user-token", 21L, "USER");
        prepareSession("blog-merchant-token", 22L, "MERCHANT");
        prepareSession("blog-admin-token", 23L, "ADMIN");
        when(blogService.saveBlog(any())).thenReturn(Result.ok(31L));
        String body = "{\"shopId\":null,\"title\":\"Lunch\",\"images\":\"/imgs/lunch.jpg\",\"content\":\"Good\"}";

        for (String token : Arrays.asList("blog-user-token", "blog-merchant-token", "blog-admin-token")) {
            mockMvc.perform(post("/blog").header("authorization", token)
                            .contentType(MediaType.APPLICATION_JSON).content(body))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data").value(31));
        }

        verify(blogService, times(3)).saveBlog(any());
    }

    @Test
    void publicationKeyRouteKeepsRoleGateAndDelegatesForAllAllowedRoles() throws Exception {
        prepareSession("key-user", 61L, "USER");
        prepareSession("key-merchant", 62L, "MERCHANT");
        prepareSession("key-admin", 63L, "ADMIN");
        when(blogPublicationService.publish(any(), any())).thenReturn(Result.ok(71L));
        String body = "{\"title\":\"Lunch\",\"images\":\"/imgs/lunch.jpg\",\"content\":\"Good\"}";
        for (String[] role : new String[][]{{"key-user", "user-key"},
                {"key-merchant", "merchant-key"}, {"key-admin", "admin-key"}}) {
            mockMvc.perform(post("/blog").header("authorization", role[0])
                            .header("Idempotency-Key", role[1]).contentType(MediaType.APPLICATION_JSON).content(body))
                    .andExpect(status().isOk()).andExpect(jsonPath("$.data").value(71));
        }
        org.mockito.ArgumentCaptor<io.github.frewily.campushub.dto.request.BlogCreateRequest> requests =
                org.mockito.ArgumentCaptor.forClass(io.github.frewily.campushub.dto.request.BlogCreateRequest.class);
        org.mockito.ArgumentCaptor<String> keys = org.mockito.ArgumentCaptor.forClass(String.class);
        verify(blogPublicationService, times(3)).publish(requests.capture(), keys.capture());
        org.junit.jupiter.api.Assertions.assertEquals(Arrays.asList("user-key", "merchant-key", "admin-key"),
                keys.getAllValues());
        for (io.github.frewily.campushub.dto.request.BlogCreateRequest request : requests.getAllValues()) {
            org.junit.jupiter.api.Assertions.assertEquals("Lunch", request.getTitle());
            org.junit.jupiter.api.Assertions.assertEquals("Good", request.getContent());
        }
        verifyNoInteractions(blogService);
    }

    @Test
    void unauthorizedKeyedPublishDoesNotReachEitherPublicationService() throws Exception {
        String body = "{\"title\":\"Lunch\",\"images\":\"/imgs/lunch.jpg\",\"content\":\"Good\"}";
        mockMvc.perform(post("/blog").header("Idempotency-Key", "valid-key")
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isUnauthorized());

        String disabledToken = "key-disabled";
        Map<Object, Object> disabled = new HashMap<>();
        disabled.put("id", "64");
        disabled.put("nickName", "disabled");
        when(stringRedisTemplate.opsForHash()).thenReturn(hashOperations);
        when(hashOperations.entries(LOGIN_USER_KEY + disabledToken)).thenReturn(disabled);
        when(accountAccessMapper.findAccountStatus(64L)).thenReturn("DISABLED");
        when(accountAccessMapper.findRoles(64L)).thenReturn(Arrays.asList("USER"));
        mockMvc.perform(post("/blog").header("authorization", disabledToken)
                        .header("Idempotency-Key", "valid-key")
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isUnauthorized());

        prepareSession("key-roleless", 65L);
        mockMvc.perform(post("/blog").header("authorization", "key-roleless")
                        .header("Idempotency-Key", "valid-key")
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isUnauthorized());
        verifyNoInteractions(blogService, blogPublicationService);
    }

    @Test
    void disabledAccountCannotPublishBlogEvenWithValidSessionAndRole() throws Exception {
        String token = "disabled-blog-token";
        Map<Object, Object> session = new HashMap<>();
        session.put("id", "24");
        session.put("nickName", "disabled-user");
        when(stringRedisTemplate.opsForHash()).thenReturn(hashOperations);
        when(hashOperations.entries(LOGIN_USER_KEY + token)).thenReturn(session);
        when(accountAccessMapper.findAccountStatus(24L)).thenReturn("DISABLED");
        when(accountAccessMapper.findRoles(24L)).thenReturn(Arrays.asList("USER"));

        mockMvc.perform(post("/blog").header("authorization", token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"title\":\"Lunch\",\"images\":\"/imgs/lunch.jpg\",\"content\":\"Good\"}"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.errorCode").value("AUTHENTICATION_FAILED"));

        verifyNoInteractions(blogService);
    }

    @Test
    void adminSessionShouldReceiveTypedForbiddenResponseForUserActivity() throws Exception {
        String token = "admin-token";
        Map<Object, Object> session = new HashMap<>();
        session.put("id", "1");
        session.put("nickName", "admin");
        when(stringRedisTemplate.opsForHash()).thenReturn(hashOperations);
        when(hashOperations.entries(LOGIN_USER_KEY + token)).thenReturn(session);
        when(stringRedisTemplate.execute(any(org.springframework.data.redis.core.script.RedisScript.class), eq(Collections.singletonList(LOGIN_USER_KEY + token)), eq("1"), anyString())).thenReturn(1L);
        when(accountAccessMapper.findAccountStatus(1L)).thenReturn("ACTIVE");
        when(accountAccessMapper.findRoles(1L)).thenReturn(Arrays.asList("ADMIN", "USER"));

        mockMvc.perform(post("/user/sign").header("authorization", token))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.errorCode").value("AUTHORIZATION_FAILED"));
    }

    @Test
    void merchantShouldCreateOnlyStoreForActiveMembership() throws Exception {
        prepareSession("merchant-token", 7L, "MERCHANT");
        when(accountAccessMapper.countActiveMerchantMembership(7L, 3L)).thenReturn(1);
        when(accountAccessMapper.countActiveMerchantMembership(7L, 4L)).thenReturn(0);
        when(shopService.createShop(any())).thenReturn(Result.ok(11L));

        mockMvc.perform(post("/shop")
                        .header("authorization", "merchant-token")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"Campus Cafe\",\"merchantId\":3,\"typeId\":1,"
                                + "\"images\":\"/imgs/cafe.jpg\",\"address\":\"Campus\",\"x\":118,\"y\":30}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data").value(11));

        mockMvc.perform(post("/shop")
                        .header("authorization", "merchant-token")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"Foreign Store\",\"merchantId\":4,\"typeId\":1,"
                                + "\"images\":\"/imgs/cafe.jpg\",\"address\":\"Campus\",\"x\":118,\"y\":30}"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.errorCode").value("AUTHORIZATION_FAILED"));
    }

    @Test
    void userCannotWriteMerchantResourcesWithNewRequestModels() throws Exception {
        prepareSession("user-token", 8L, "USER");
        mockMvc.perform(post("/shop")
                        .header("authorization", "user-token")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"Campus Cafe\",\"merchantId\":3,\"typeId\":1,"
                                + "\"images\":\"/imgs/cafe.jpg\",\"address\":\"Campus\",\"x\":118,\"y\":30}"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.errorCode").value("AUTHORIZATION_FAILED"));
        mockMvc.perform(post("/voucher")
                        .header("authorization", "user-token")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"shopId\":1,\"title\":\"Coupon\",\"payValue\":100,\"actualValue\":200}"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.errorCode").value("AUTHORIZATION_FAILED"));
    }

    @Test
    void merchantPromotionRequiresStoreOwnershipWithNewRequestModel() throws Exception {
        prepareSession("merchant-token", 7L, "MERCHANT");
        when(accountAccessMapper.findMerchantIdByShopId(11L)).thenReturn(3L);
        when(accountAccessMapper.findMerchantIdByShopId(12L)).thenReturn(4L);
        when(accountAccessMapper.countActiveMerchantMembership(7L, 3L)).thenReturn(1);
        when(voucherService.addVoucher(any())).thenReturn(Result.ok(20L));
        mockMvc.perform(post("/voucher")
                        .header("authorization", "merchant-token")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"shopId\":11,\"title\":\"Coupon\",\"payValue\":100,\"actualValue\":200}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data").value(20));
        mockMvc.perform(post("/voucher")
                        .header("authorization", "merchant-token")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"shopId\":12,\"title\":\"Coupon\",\"payValue\":100,\"actualValue\":200}"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.errorCode").value("AUTHORIZATION_FAILED"));
    }

    @Test
    void merchantPartialUpdateRequiresOwnershipOfTargetId() throws Exception {
        prepareSession("merchant-token", 7L, "MERCHANT");
        when(accountAccessMapper.findMerchantIdByShopId(11L)).thenReturn(3L);
        when(accountAccessMapper.findMerchantIdByShopId(12L)).thenReturn(4L);
        when(accountAccessMapper.countActiveMerchantMembership(7L, 3L)).thenReturn(1);
        when(shopService.updateShop(any())).thenReturn(Result.ok());
        mockMvc.perform(put("/shop").header("authorization", "merchant-token")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"id\":11,\"name\":\"Cafe\",\"merchantId\":4}"))
                .andExpect(status().isOk());
        mockMvc.perform(put("/shop").header("authorization", "merchant-token")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"id\":12,\"name\":\"Foreign\",\"merchantId\":3}"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.errorCode").value("AUTHORIZATION_FAILED"));
    }

    private void prepareSession(String token, Long userId, String... roles) {
        Map<Object, Object> session = new HashMap<>();
        session.put("id", userId.toString());
        session.put("nickName", "test-user");
        when(stringRedisTemplate.opsForHash()).thenReturn(hashOperations);
        when(hashOperations.entries(LOGIN_USER_KEY + token)).thenReturn(session);
        if (roles.length > 0) when(stringRedisTemplate.execute(any(org.springframework.data.redis.core.script.RedisScript.class), eq(Collections.singletonList(LOGIN_USER_KEY + token)), eq(userId.toString()), anyString())).thenReturn(1L);
        when(accountAccessMapper.findAccountStatus(userId)).thenReturn("ACTIVE");
        when(accountAccessMapper.findRoles(userId)).thenReturn(Arrays.asList(roles));
    }
}
