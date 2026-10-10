package io.github.frewily.campushub.config;

import io.github.frewily.campushub.controller.UserController;
import io.github.frewily.campushub.controller.ShopController;
import io.github.frewily.campushub.controller.VoucherController;
import io.github.frewily.campushub.controller.BlogController;
import io.github.frewily.campushub.controller.BlogCommentsController;
import io.github.frewily.campushub.dto.Result;
import io.github.frewily.campushub.mapper.AccountAccessMapper;
import io.github.frewily.campushub.security.RedisTokenAuthenticationFilter;
import io.github.frewily.campushub.security.ResourceAuthorizationService;
import io.github.frewily.campushub.security.SecurityErrorResponder;
import io.github.frewily.campushub.service.IUserInfoService;
import io.github.frewily.campushub.service.IUserService;
import io.github.frewily.campushub.service.IShopService;
import io.github.frewily.campushub.service.IVoucherService;
import io.github.frewily.campushub.service.IBlogService;
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

import static io.github.frewily.campushub.utils.RedisConstants.LOGIN_USER_KEY;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.times;
import static org.mockito.ArgumentMatchers.any;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
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
    private IUserInfoService userInfoService;
    @MockBean
    private IShopService shopService;
    @MockBean
    private IVoucherService voucherService;
    @MockBean
    private IBlogService blogService;
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
            ShopController.class,
            VoucherController.class,
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
        when(accountAccessMapper.findAccountStatus(userId)).thenReturn("ACTIVE");
        when(accountAccessMapper.findRoles(userId)).thenReturn(Arrays.asList(roles));
    }
}
