package io.github.frewily.campushub.merchant.api;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.frewily.campushub.config.GlobalExceptionHandler;
import io.github.frewily.campushub.config.SecurityConfig;
import io.github.frewily.campushub.entity.ShopType;
import io.github.frewily.campushub.mapper.AccountAccessMapper;
import io.github.frewily.campushub.merchant.application.ShopTypeQueryService;
import io.github.frewily.campushub.security.RedisTokenAuthenticationFilter;
import io.github.frewily.campushub.security.SecurityErrorResponder;
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
import java.time.LocalDateTime;
import java.util.*;

import static io.github.frewily.campushub.utils.RedisConstants.LOGIN_USER_KEY;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(classes = ShopTypeHttpContractTest.TestApplication.class)
@AutoConfigureMockMvc
class ShopTypeHttpContractTest {
    private long nextIdentity = 995201L;
    @Autowired private MockMvc mvc;
    @MockBean private ShopTypeQueryService service;
    @MockBean private StringRedisTemplate redis;
    @MockBean private HashOperations<String, Object, Object> hashes;
    @MockBean private AccountAccessMapper accounts;

    @SpringBootConfiguration
    @EnableAutoConfiguration
    @Import({ShopTypeController.class, SecurityConfig.class, SecurityErrorResponder.class,
            RedisTokenAuthenticationFilter.class, GlobalExceptionHandler.class})
    static class TestApplication { }

    @AfterEach
    void clearIdentity() {
        try { assertNull(UserHolder.getUser()); }
        finally { UserHolder.removeUser(); }
    }

    @Test
    void anonymousGetKeepsEntitySerializationWithoutTimestamps() throws Exception {
        ShopType row = new ShopType().setId(9007199254740993L).setName("synthetic category").setIcon("/category.png")
                .setSort(4).setCreateTime(LocalDateTime.of(2026, 10, 18, 12, 0)).setUpdateTime(LocalDateTime.of(2026, 10, 18, 13, 0));
        when(service.queryList()).thenReturn(Collections.singletonList(row));
        String body = mvc.perform(get("/shop-type/list")).andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true)).andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        JsonNode item = new ObjectMapper().readTree(body).path("data").get(0);
        Set<String> fields = new HashSet<>(); item.fieldNames().forEachRemaining(fields::add);
        assertEquals(new HashSet<>(Arrays.asList("id", "name", "icon", "sort")), fields);
        assertTrue(item.path("id").isIntegralNumber());
        assertEquals(9007199254740993L, item.path("id").asLong());
        verify(service).queryList();
    }

    @Test
    void emptyCatalogRemainsAnEmptyArray() throws Exception {
        when(service.queryList()).thenReturn(Collections.emptyList());
        mvc.perform(get("/shop-type/list")).andExpect(status().isOk()).andExpect(jsonPath("$.data").isEmpty());
    }

    @Test
    void allValidRolesCanUseThePublicReadWithoutMerchantMembership() throws Exception {
        when(service.queryList()).thenReturn(Collections.emptyList());
        for (String role : Arrays.asList("USER", "MERCHANT", "ADMIN")) {
            session(role, "ACTIVE", role);
            mvc.perform(get("/shop-type/list").header("authorization", role)).andExpect(status().isOk());
        }
        verify(service, times(3)).queryList();
        verify(accounts, never()).countActiveMerchantMembership(anyLong(), anyLong());
    }

    @Test
    void expiredDisabledAndUnknownRoleTokensDoNotCloseThePublicRoute() throws Exception {
        when(service.queryList()).thenReturn(Collections.emptyList());
        session("disabled", "DISABLED", "USER");
        session("unknown", "ACTIVE", "AUDITOR");
        when(redis.opsForHash()).thenReturn(hashes);
        when(hashes.entries(LOGIN_USER_KEY + "expired")).thenReturn(Collections.emptyMap());
        for (String token : Arrays.asList("disabled", "unknown", "expired")) {
            mvc.perform(get("/shop-type/list").header("authorization", token)).andExpect(status().isOk());
        }
        verify(service, times(3)).queryList();
    }

    @Test
    void dependencyFailureRemainsGeneric500InsteadOfPartialSuccess() throws Exception {
        when(service.queryList()).thenThrow(new IllegalStateException("synthetic-private-category-detail"));
        String body = mvc.perform(get("/shop-type/list")).andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.success").value(false)).andExpect(jsonPath("$.errorCode").value("INTERNAL_ERROR"))
                .andReturn().getResponse().getContentAsString();
        assertFalse(body.contains("synthetic-private-category-detail"));
    }

    private void session(String token, String status, String role) {
        long id = nextIdentity++;
        Map<Object, Object> identity = new HashMap<>();
        identity.put("id", Long.toString(id)); identity.put("nickName", "synthetic");
        when(redis.opsForHash()).thenReturn(hashes);
        when(hashes.entries(LOGIN_USER_KEY + token)).thenReturn(identity);
        when(accounts.findAccountStatus(id)).thenReturn(status);
        when(accounts.findRoles(id)).thenReturn(Collections.singletonList(role));
    }
}
