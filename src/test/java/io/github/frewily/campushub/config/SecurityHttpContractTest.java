package io.github.frewily.campushub.config;

import io.github.frewily.campushub.controller.UserController;
import io.github.frewily.campushub.controller.ShopController;
import io.github.frewily.campushub.dto.Result;
import io.github.frewily.campushub.mapper.AccountAccessMapper;
import io.github.frewily.campushub.security.RedisTokenAuthenticationFilter;
import io.github.frewily.campushub.security.ResourceAuthorizationService;
import io.github.frewily.campushub.security.SecurityErrorResponder;
import io.github.frewily.campushub.service.IUserInfoService;
import io.github.frewily.campushub.service.IUserService;
import io.github.frewily.campushub.service.IShopService;
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

import static io.github.frewily.campushub.utils.RedisConstants.LOGIN_USER_KEY;
import static org.mockito.Mockito.when;
import static org.mockito.ArgumentMatchers.any;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
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
                        .content("{\"name\":\"Campus Cafe\",\"merchantId\":3}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data").value(11));

        mockMvc.perform(post("/shop")
                        .header("authorization", "merchant-token")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"Foreign Store\",\"merchantId\":4}"))
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
