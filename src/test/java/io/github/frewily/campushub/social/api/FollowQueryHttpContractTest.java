package io.github.frewily.campushub.social.api;

import com.fasterxml.jackson.databind.*;
import io.github.frewily.campushub.config.GlobalExceptionHandler;
import io.github.frewily.campushub.config.SecurityConfig;
import io.github.frewily.campushub.controller.FollowController;
import io.github.frewily.campushub.dto.Result;
import io.github.frewily.campushub.dto.UserDTO;
import io.github.frewily.campushub.mapper.AccountAccessMapper;
import io.github.frewily.campushub.security.RedisTokenAuthenticationFilter;
import io.github.frewily.campushub.security.SecurityErrorResponder;
import io.github.frewily.campushub.service.IFollowService;
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
    @Autowired private MockMvc mvc;
    @MockBean private FollowQueryService queries;
    @MockBean private IFollowService writes;
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
    void anonymousRequestsToBothQueriesRemainUnauthorized() throws Exception {
        for (String route : routes("9")) mvc.perform(get(route)).andExpect(status().isUnauthorized());
        verifyNoInteractions(queries, writes);
    }

    @Test
    void allValidRolesReadWithTheSessionActorAndKeepTheOriginalResponses() throws Exception {
        long actor = 41;
        for (String role : Arrays.asList("USER", "MERCHANT", "ADMIN")) {
            session(role, actor, "ACTIVE", role);
            when(queries.isFollow(actor, 9L)).thenReturn(true);
            UserDTO item = new UserDTO(); item.setId(9007199254740993L); item.setNickName("common"); item.setIcon("icon.png");
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
            verify(queries).isFollow(actor, 9L);
            verify(queries).common(actor, 9L);
            actor++;
        }
        verifyNoMoreInteractions(queries);
        verifyNoInteractions(writes);
    }

    @Test
    void invalidDisabledAndUnknownRoleSessionsCannotReachQueryServices() throws Exception {
        session("disabled", 51L, "DISABLED", "USER");
        session("unknown", 52L, "ACTIVE", "AUDITOR");
        session("roleless", 53L, "ACTIVE");
        when(redis.opsForHash()).thenReturn(hashes);
        when(hashes.entries(LOGIN_USER_KEY + "expired")).thenReturn(Collections.emptyMap());
        for (String token : Arrays.asList("disabled", "unknown", "roleless", "expired")) {
            for (String route : routes("9")) mvc.perform(get(route).header("authorization", token))
                    .andExpect(status().isUnauthorized()).andExpect(jsonPath("$.errorCode").value("AUTHENTICATION_FAILED"));
        }
        verifyNoInteractions(queries, writes);
    }

    @Test
    void bothIdsStillRequireAPositiveJavaLong() throws Exception {
        session("user", 61L, "ACTIVE", "USER");
        for (String id : Arrays.asList("0", "-1", "9223372036854775808", "not-a-number")) {
            for (String route : routes(id)) mvc.perform(get(route).header("authorization", "user"))
                    .andExpect(status().isBadRequest()).andExpect(jsonPath("$.errorCode").value("VALIDATION_FAILED"));
        }
        verifyNoInteractions(queries, writes);
    }

    @Test
    void selfQueriesDoNotInheritTheWriteSelfFollowConflict() throws Exception {
        session("user", 71L, "ACTIVE", "USER");
        when(queries.common(71L, 71L)).thenReturn(Collections.emptyList());
        mvc.perform(get("/follow/or/not/71").header("authorization", "user"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data").value(false));
        mvc.perform(get("/follow/common/71").header("authorization", "user"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data").isEmpty());
        verifyNoInteractions(writes);
    }

    @Test
    void queryFailuresKeepGeneric500AndDoNotLeakDetails() throws Exception {
        session("user", 81L, "ACTIVE", "USER");
        when(queries.isFollow(81L, 9L)).thenThrow(new IllegalStateException("synthetic-private-query-detail"));
        when(queries.common(81L, 9L)).thenThrow(new NumberFormatException("synthetic-private-query-detail"));
        for (String route : routes("9")) {
            String body = mvc.perform(get(route).header("authorization", "user"))
                    .andExpect(status().isInternalServerError()).andExpect(jsonPath("$.errorCode").value("INTERNAL_ERROR"))
                    .andReturn().getResponse().getContentAsString();
            assertFalse(body.contains("synthetic-private-query-detail"));
        }
        verifyNoInteractions(writes);
    }

    @Test
    void followWriteRouteStillUsesTheLegacyWriteServiceOnly() throws Exception {
        session("user", 91L, "ACTIVE", "USER");
        when(writes.follow(9L, true)).thenReturn(Result.ok());
        mvc.perform(put("/follow/9/true").header("authorization", "user"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.success").value(true)).andExpect(jsonPath("$.data").doesNotExist());
        verify(writes).follow(9L, true);
        verifyNoInteractions(queries);
    }

    private List<String> routes(String id) { return Arrays.asList("/follow/or/not/" + id, "/follow/common/" + id); }

    private void session(String token, Long id, String status, String... roles) {
        Map<Object, Object> identity = new HashMap<>(); identity.put("id", id.toString()); identity.put("nickName", "synthetic");
        when(redis.opsForHash()).thenReturn(hashes);
        when(hashes.entries(LOGIN_USER_KEY + token)).thenReturn(identity);
        when(accounts.findAccountStatus(id)).thenReturn(status);
        when(accounts.findRoles(id)).thenReturn(Arrays.asList(roles));
    }
}
