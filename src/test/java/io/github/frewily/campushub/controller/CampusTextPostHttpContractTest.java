package io.github.frewily.campushub.controller;

import io.github.frewily.campushub.config.GlobalExceptionHandler;
import io.github.frewily.campushub.config.SecurityConfig;
import io.github.frewily.campushub.dto.Result;
import io.github.frewily.campushub.entity.Blog;
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
import org.springframework.test.web.servlet.MvcResult;

import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(classes = CampusTextPostHttpContractTest.TestApplication.class)
@AutoConfigureMockMvc
class CampusTextPostHttpContractTest {
    private static final String BODY = "{\"title\":\"Campus\",\"content\":\"Hello\"}";

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
    void clearUser() {
        try {
            assertNull(UserHolder.getUser(), "authentication filter must clear identity after every request");
        } finally {
            UserHolder.removeUser();
        }
    }

    @Test
    void campusPostAcceptsOptionalImagesForAllThreeRolesAndReturnsLegacyNumericId() throws Exception {
        when(blogService.saveBlog(any())).thenReturn(Result.ok(22L));
        for (String role : new String[]{"USER", "MERCHANT", "ADMIN"}) {
            String token = role.toLowerCase() + "-token";
            prepareSession(token, "ACTIVE", role, 7001L);
            MvcResult result = mockMvc.perform(post("/blog").header("authorization", token)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(BODY.replace("}", ",\"id\":999,\"userId\":88,\"liked\":777,"
                                    + "\"comments\":666,\"createTime\":\"2000-01-01T00:00:00\","
                                    + "\"storeImageValid\":true,\"ignored\":true}")))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.success").value(true))
                    .andExpect(jsonPath("$.data").value(22))
                    .andReturn();
            assertTrue(result.getResponse().getContentAsString(StandardCharsets.UTF_8).contains("\"data\":22"));
            assertNull(UserHolder.getUser());
        }

        org.mockito.ArgumentCaptor<Blog> captor = org.mockito.ArgumentCaptor.forClass(Blog.class);
        verify(blogService, times(3)).saveBlog(captor.capture());
        for (Blog blog : captor.getAllValues()) {
            assertNull(blog.getId());
            assertNull(blog.getUserId());
            assertNull(blog.getCreateTime());
            assertNull(blog.getUpdateTime());
            assertEquals(0, blog.getLiked());
            assertEquals(0, blog.getComments());
            assertNull(blog.getShopId());
            assertEquals("", blog.getImages());
        }
    }

    @Test
    void identicalPublicationKeyMustNotCreateASecondPostOnHttpRetry() throws Exception {
        prepareSession("retry-token", "ACTIVE", "USER", 7001L);
        when(blogPublicationService.publish(any(), eq("synthetic-retry-key")))
                .thenReturn(Result.ok(31L), Result.ok(31L));
        for (int attempt = 0; attempt < 2; attempt++) {
            mockMvc.perform(post("/blog").header("authorization", "retry-token")
                            .header("Idempotency-Key", "synthetic-retry-key")
                            .contentType(MediaType.APPLICATION_JSON).content(BODY))
                    .andExpect(status().isOk()).andExpect(jsonPath("$.data").value(31));
        }
        verify(blogPublicationService, times(2)).publish(any(), eq("synthetic-retry-key"));
        verifyNoInteractions(blogService);
    }

    @Test
    void malformedPublicationKeysAreBadRequestsAndNeverReachEitherService() throws Exception {
        prepareSession("key-token", "ACTIVE", "USER", 7001L);
        String[] invalidKeys = {"", " ", "leading space", " trailing", "comma,key", "slash/key",
                "café", repeat('k', 65)};
        for (String key : invalidKeys) {
            mockMvc.perform(post("/blog").header("authorization", "key-token")
                            .header("Idempotency-Key", key).contentType(MediaType.APPLICATION_JSON).content(BODY))
                    .andExpect(status().isBadRequest());
        }
        mockMvc.perform(post("/blog").header("authorization", "key-token")
                        .header("Idempotency-Key", "first", "second")
                        .contentType(MediaType.APPLICATION_JSON).content(BODY))
                .andExpect(status().isBadRequest());
        verifyNoInteractions(blogService, blogPublicationService);
    }

    @Test
    void authenticatedPublicationKeyIsForwardedAndRequestWhitelistAndValidationRemainActive() throws Exception {
        when(blogPublicationService.publish(any(), any())).thenReturn(Result.ok(44L));
        for (String role : new String[]{"USER", "MERCHANT", "ADMIN"}) {
            String token = role.toLowerCase() + "-key-token";
            prepareSession(token, "ACTIVE", role, 7001L);
            mockMvc.perform(post("/blog").header("authorization", token)
                            .header("Idempotency-Key", "Key_9-A")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"title\":\"Campus\",\"images\":\"  /raw  \",\"content\":\"Hello\","
                                    + "\"id\":999,\"userId\":88,\"liked\":777,\"comments\":666,"
                                    + "\"createTime\":\"2000-01-01T00:00:00\",\"ignored\":true}"))
                    .andExpect(status().isOk()).andExpect(jsonPath("$.data").value(44));
        }
        mockMvc.perform(post("/blog").header("authorization", "user-key-token")
                        .header("Idempotency-Key", "valid-key")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"title\":\" \",\"content\":\"Hello\"}"))
                .andExpect(status().isBadRequest());

        org.mockito.ArgumentCaptor<io.github.frewily.campushub.dto.request.BlogCreateRequest> captor =
                org.mockito.ArgumentCaptor.forClass(io.github.frewily.campushub.dto.request.BlogCreateRequest.class);
        verify(blogPublicationService, times(3)).publish(captor.capture(), eq("Key_9-A"));
        for (io.github.frewily.campushub.dto.request.BlogCreateRequest request : captor.getAllValues()) {
            assertNull(request.getShopId());
            assertEquals("Campus", request.getTitle());
            assertEquals("  /raw  ", request.getImages());
            assertEquals("Hello", request.getContent());
        }
        verifyNoInteractions(blogService);
    }

    @Test
    void authenticatedRequestsWithoutPublicationKeyKeepUsingLegacyService() throws Exception {
        when(blogService.saveBlog(any())).thenReturn(Result.ok(45L));
        prepareSession("legacy-token", "ACTIVE", "USER", 7001L);
        mockMvc.perform(post("/blog").header("authorization", "legacy-token")
                        .contentType(MediaType.APPLICATION_JSON).content(BODY))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data").value(45));
        verify(blogService).saveBlog(any());
        verifyNoInteractions(blogPublicationService);
    }

    @Test
    void invalidIdentityStatesAreUnauthorizedAndNeverReachPublicationService() throws Exception {
        when(stringRedisTemplate.opsForHash()).thenReturn(hashOperations);
        when(hashOperations.entries(RedisConstants.LOGIN_USER_KEY + "missing-token"))
                .thenReturn(Collections.<Object, Object>emptyMap());
        mockMvc.perform(post("/blog").contentType(MediaType.APPLICATION_JSON).content(BODY))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(post("/blog").header("authorization", "missing-token")
                        .contentType(MediaType.APPLICATION_JSON).content(BODY))
                .andExpect(status().isUnauthorized());

        prepareSession("disabled-token", "DISABLED", "USER", 7001L);
        mockMvc.perform(post("/blog").header("authorization", "disabled-token")
                        .contentType(MediaType.APPLICATION_JSON).content(BODY))
                .andExpect(status().isUnauthorized());
        prepareSession("unknown-role-token", "ACTIVE", "AUDITOR", 7001L);
        mockMvc.perform(post("/blog").header("authorization", "unknown-role-token")
                        .contentType(MediaType.APPLICATION_JSON).content(BODY))
                .andExpect(status().isUnauthorized());
        prepareSession("roleless-token", "ACTIVE", null, 7001L);
        mockMvc.perform(post("/blog").header("authorization", "roleless-token")
                        .contentType(MediaType.APPLICATION_JSON).content(BODY))
                .andExpect(status().isUnauthorized());
        verifyNoInteractions(blogService);
        verifyNoInteractions(blogPublicationService);
    }

    @Test
    void storeImageGuardCannotBeForgedAndOversizedBlankImagesRemainInvalid() throws Exception {
        prepareSession("user-token", "ACTIVE", "USER", 7001L);
        mockMvc.perform(post("/blog").header("authorization", "user-token")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"shopId\":11,\"title\":\"Cafe\",\"images\":\" \","
                                + "\"content\":\"Good\",\"storeImageValid\":true}"))
                .andExpect(status().isBadRequest());

        StringBuilder oversizedBlank = new StringBuilder(2049);
        for (int i = 0; i < 2049; i++) oversizedBlank.append(' ');
        mockMvc.perform(post("/blog").header("authorization", "user-token")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"title\":\"Campus\",\"images\":\"" + oversizedBlank
                                + "\",\"content\":\"Hello\"}"))
                .andExpect(status().isBadRequest());
        verifyNoInteractions(blogService);
    }

    @Test
    void authenticatedRequestDoesNotLeakIdentityIntoFollowingAnonymousRequest() throws Exception {
        when(blogService.saveBlog(any())).thenReturn(Result.ok(22L));
        prepareSession("user-token", "ACTIVE", "USER", 7001L);
        mockMvc.perform(post("/blog").header("authorization", "user-token")
                        .contentType(MediaType.APPLICATION_JSON).content(BODY))
                .andExpect(status().isOk());
        assertNull(UserHolder.getUser());

        mockMvc.perform(post("/blog").contentType(MediaType.APPLICATION_JSON).content(BODY))
                .andExpect(status().isUnauthorized());
        assertNull(UserHolder.getUser());
        verify(blogService, times(1)).saveBlog(any());
    }

    private void prepareSession(String token, String status, String role, long userId) {
        when(stringRedisTemplate.opsForHash()).thenReturn(hashOperations);
        Map<Object, Object> session = new HashMap<>();
        session.put("id", Long.toString(userId));
        session.put("nickName", "campus-user");
        when(hashOperations.entries(RedisConstants.LOGIN_USER_KEY + token)).thenReturn(session);
        when(accountAccessMapper.findAccountStatus(userId)).thenReturn(status);
        when(accountAccessMapper.findRoles(userId)).thenReturn(role == null
                ? Collections.<String>emptyList() : Collections.singletonList(role));
    }

    private static String repeat(char value, int count) {
        char[] chars = new char[count];
        java.util.Arrays.fill(chars, value);
        return new String(chars);
    }
}
