package io.github.frewily.campushub.social.api;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.frewily.campushub.config.GlobalExceptionHandler;
import io.github.frewily.campushub.config.SecurityConfig;
import io.github.frewily.campushub.dto.UserDTO;
import io.github.frewily.campushub.exception.BusinessException;
import io.github.frewily.campushub.exception.ErrorCode;
import io.github.frewily.campushub.mapper.AccountAccessMapper;
import io.github.frewily.campushub.security.RedisTokenAuthenticationFilter;
import io.github.frewily.campushub.security.SecurityErrorResponder;
import io.github.frewily.campushub.social.application.FollowCommandService;
import io.github.frewily.campushub.social.application.FollowQueryService;
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

import java.nio.charset.StandardCharsets;
import java.util.*;

import static io.github.frewily.campushub.utils.RedisConstants.LOGIN_USER_KEY;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(classes = FollowQueryHttpContractTest.TestApplication.class)
@AutoConfigureMockMvc
class FollowQueryHttpContractTest {
    private static final String LARGE_TARGET = "9007199254740993";

    @Autowired private MockMvc mvc;
    @MockBean private FollowQueryService queries;
    @MockBean private FollowCommandService writes;
    @MockBean private StringRedisTemplate redis;
    @MockBean private HashOperations<String, Object, Object> hashes;
    @MockBean private AccountAccessMapper accounts;

    @SpringBootConfiguration
    @EnableAutoConfiguration
    @Import({FollowQueryController.class, FollowController.class, SecurityConfig.class, SecurityErrorResponder.class,
            RedisTokenAuthenticationFilter.class, GlobalExceptionHandler.class})
    static class TestApplication { }

    @AfterEach
    void clearIdentity() {
        try { assertNull(UserHolder.getUser()); }
        finally { UserHolder.removeUser(); }
    }

    @Test
    void anonymousRequestsToQueriesAndWriteRemainUnauthorized() throws Exception {
        for (String route : queryRoutes("9")) mvc.perform(get(route)).andExpect(status().isUnauthorized());
        mvc.perform(put("/follow/9/true")).andExpect(status().isUnauthorized());
        verifyNoInteractions(queries, writes);
    }

    @Test
    void allThreeAllowedRolesUseSessionActorForQueriesAndWrites() throws Exception {
        long actor = 41L;
        for (String role : Arrays.asList("USER", "MERCHANT", "ADMIN")) {
            session(role, actor, "ACTIVE", role);
            when(queries.isFollow(actor, 9L)).thenReturn(true);
            UserDTO item = new UserDTO();
            item.setId(9007199254740993L); item.setNickName("common"); item.setIcon("icon.png");
            when(queries.common(actor, 9L)).thenReturn(Collections.singletonList(item));

            mvc.perform(get("/follow/or/not/9").header("authorization", role).param("userId", "999"))
                    .andExpect(status().isOk()).andExpect(jsonPath("$.data").value(true));
            String body = mvc.perform(get("/follow/common/9").header("authorization", role).param("userId", "999"))
                    .andExpect(status().isOk()).andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
            JsonNode data = new ObjectMapper().readTree(body).path("data").get(0);
            Set<String> fields = new HashSet<>(); data.fieldNames().forEachRemaining(fields::add);
            assertEquals(new HashSet<>(Arrays.asList("id", "nickName", "icon")), fields);
            assertTrue(data.path("id").isIntegralNumber());
            assertEquals(9007199254740993L, data.path("id").asLong());

            mvc.perform(put("/follow/" + LARGE_TARGET + "/true").header("authorization", role).param("userId", "999"))
                    .andExpect(status().isOk()).andExpect(jsonPath("$.success").value(true))
                    .andExpect(jsonPath("$.data").doesNotExist());
            verify(queries).isFollow(actor, 9L);
            verify(queries).common(actor, 9L);
            verify(writes).follow(actor, 9007199254740993L, true);
            actor++;
        }
        verifyNoMoreInteractions(queries, writes);
    }

    @Test
    void invalidDisabledUnknownRoleRolelessAndExpiredSessionsCannotReachServices() throws Exception {
        session("disabled", 51L, "DISABLED", "USER");
        session("unknown", 52L, "ACTIVE", "AUDITOR");
        session("roleless", 53L, "ACTIVE");
        when(redis.opsForHash()).thenReturn(hashes);
        when(hashes.entries(LOGIN_USER_KEY + "expired")).thenReturn(Collections.emptyMap());
        for (String token : Arrays.asList("disabled", "unknown", "roleless", "expired")) {
            for (String route : queryRoutes("9")) mvc.perform(get(route).header("authorization", token))
                    .andExpect(status().isUnauthorized()).andExpect(jsonPath("$.errorCode").value("AUTHENTICATION_FAILED"));
            mvc.perform(put("/follow/9/true").header("authorization", token))
                    .andExpect(status().isUnauthorized()).andExpect(jsonPath("$.errorCode").value("AUTHENTICATION_FAILED"));
        }
        verifyNoInteractions(queries, writes);
    }

    @Test
    void writeTargetMustBePositiveLongAndFollowFlagMustBeBoolean() throws Exception {
        session("user", 61L, "ACTIVE", "USER");
        for (String id : Arrays.asList("0", "-1", "9223372036854775808", "not-a-number")) {
            mvc.perform(put("/follow/" + id + "/true").header("authorization", "user"))
                    .andExpect(status().isBadRequest()).andExpect(jsonPath("$.errorCode").value("VALIDATION_FAILED"));
        }
        mvc.perform(put("/follow/9/not-a-boolean").header("authorization", "user"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.errorCode").value("VALIDATION_FAILED"));
        verifyNoInteractions(writes);
    }

    @Test
    void bothIdsStillRequireAPositiveJavaLong() throws Exception {
        session("user", 62L, "ACTIVE", "USER");
        for (String id : Arrays.asList("0", "-1", "9223372036854775808", "not-a-number")) {
            for (String route : queryRoutes(id)) mvc.perform(get(route).header("authorization", "user"))
                    .andExpect(status().isBadRequest()).andExpect(jsonPath("$.errorCode").value("VALIDATION_FAILED"));
        }
        verifyNoInteractions(queries, writes);
    }

    @Test
    void queryFailuresKeepGeneric500AndDoNotLeakDetails() throws Exception {
        session("user", 82L, "ACTIVE", "USER");
        when(queries.isFollow(82L, 9L)).thenThrow(new IllegalStateException("synthetic-private-query-detail"));
        when(queries.common(82L, 9L)).thenThrow(new NumberFormatException("synthetic-private-query-detail"));
        for (String route : queryRoutes("9")) {
            String body = mvc.perform(get(route).header("authorization", "user"))
                    .andExpect(status().isInternalServerError()).andExpect(jsonPath("$.errorCode").value("INTERNAL_ERROR"))
                    .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
            assertFalse(body.contains("synthetic-private-query-detail"));
        }
        verify(queries).isFollow(82L, 9L);
        verify(queries).common(82L, 9L);
        verifyNoInteractions(writes);
        verifyNoMoreInteractions(queries);
    }

    @Test
    void selfFollowAndSelfUnfollowBothReturnConflict() throws Exception {
        session("user", 71L, "ACTIVE", "USER");
        doThrow(new BusinessException(ErrorCode.CONFLICT, "不能关注自己"))
                .when(writes).follow(71L, 71L, true);
        doThrow(new BusinessException(ErrorCode.CONFLICT, "不能关注自己"))
                .when(writes).follow(71L, 71L, false);

        for (String flag : Arrays.asList("true", "false")) {
            mvc.perform(put("/follow/71/" + flag).header("authorization", "user"))
                    .andExpect(status().isConflict()).andExpect(jsonPath("$.errorCode").value("CONFLICT"));
        }
        verify(writes).follow(71L, 71L, true);
        verify(writes).follow(71L, 71L, false);
        verifyNoInteractions(queries);
        verifyNoMoreInteractions(writes);
    }

    @Test
    void writeFailuresKeepGeneric500AndDoNotLeakDetails() throws Exception {
        session("user", 81L, "ACTIVE", "USER");
        doThrow(new IllegalStateException("synthetic-private-write-detail"))
                .when(writes).follow(81L, 9L, true);
        String body = mvc.perform(put("/follow/9/true").header("authorization", "user"))
                .andExpect(status().isInternalServerError()).andExpect(jsonPath("$.errorCode").value("INTERNAL_ERROR"))
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        assertFalse(body.contains("synthetic-private-write-detail"));
        verify(writes).follow(81L, 9L, true);
        verifyNoInteractions(queries);
    }

    @Test
    void selfQueriesDoNotInheritTheWriteSelfFollowConflict() throws Exception {
        session("user", 91L, "ACTIVE", "USER");
        when(queries.common(91L, 91L)).thenReturn(Collections.emptyList());
        mvc.perform(get("/follow/or/not/91").header("authorization", "user"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data").value(false));
        mvc.perform(get("/follow/common/91").header("authorization", "user"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data").isEmpty());
        verifyNoInteractions(writes);
    }

    private List<String> queryRoutes(String id) {
        return Arrays.asList("/follow/or/not/" + id, "/follow/common/" + id);
    }

    private void session(String token, Long id, String status, String... roles) {
        Map<Object, Object> identity = new HashMap<>();
        identity.put("id", id.toString()); identity.put("nickName", "synthetic");
        when(redis.opsForHash()).thenReturn(hashes);
        when(hashes.entries(LOGIN_USER_KEY + token)).thenReturn(identity);
        when(accounts.findAccountStatus(id)).thenReturn(status);
        when(accounts.findRoles(id)).thenReturn(Arrays.asList(roles));
    }
}
