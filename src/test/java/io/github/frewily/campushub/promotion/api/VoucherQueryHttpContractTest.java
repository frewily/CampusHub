package io.github.frewily.campushub.promotion.api;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.frewily.campushub.config.GlobalExceptionHandler;
import io.github.frewily.campushub.config.SecurityConfig;
import io.github.frewily.campushub.entity.Voucher;
import io.github.frewily.campushub.mapper.AccountAccessMapper;
import io.github.frewily.campushub.promotion.application.VoucherQueryService;
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

@SpringBootTest(classes = VoucherQueryHttpContractTest.TestApplication.class)
@AutoConfigureMockMvc
class VoucherQueryHttpContractTest {
    @Autowired private MockMvc mvc;
    @MockBean private VoucherQueryService service;
    @MockBean private StringRedisTemplate redis;
    @MockBean private HashOperations<String, Object, Object> hashes;
    @MockBean private AccountAccessMapper accounts;

    @SpringBootConfiguration
    @EnableAutoConfiguration
    @Import({VoucherQueryController.class, SecurityConfig.class, SecurityErrorResponder.class,
            RedisTokenAuthenticationFilter.class, GlobalExceptionHandler.class})
    static class TestApplication { }

    @AfterEach
    void clearUserHolder() {
        try { assertNull(UserHolder.getUser()); }
        finally { UserHolder.removeUser(); }
    }

    @Test
    void anonymousReadPreservesLargeEntityNumbersAndSelectedEntityFields() throws Exception {
        LocalDateTime begin = LocalDateTime.of(2027, 1, 1, 10, 0);
        LocalDateTime end = LocalDateTime.of(2027, 1, 1, 11, 30, 15);
        Voucher voucher = new Voucher().setId(9007199254740993L).setShopId(Long.MAX_VALUE)
                .setTitle("synthetic voucher").setSubTitle("synthetic subtitle").setRules("synthetic rules")
                .setPayValue(9007199254740995L).setActualValue(9007199254740997L).setType(1).setStock(0)
                .setBeginTime(begin).setEndTime(end);
        when(service.queryVoucherOfShop(Long.MAX_VALUE)).thenReturn(Collections.singletonList(voucher));

        String body = mvc.perform(get("/voucher/list/{shopId}", Long.MAX_VALUE))
                .andExpect(status().isOk()).andExpect(jsonPath("$.success").value(true))
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);

        JsonNode item = new ObjectMapper().readTree(body).path("data").get(0);
        Set<String> fields = new HashSet<>(); item.fieldNames().forEachRemaining(fields::add);
        assertEquals(new HashSet<>(Arrays.asList("id", "shopId", "title", "subTitle", "rules", "payValue",
                "actualValue", "type", "stock", "beginTime", "endTime")), fields);
        assertTrue(item.path("id").isIntegralNumber());
        assertEquals(9007199254740993L, item.path("id").asLong());
        assertEquals(Long.MAX_VALUE, item.path("shopId").asLong());
        assertEquals(9007199254740995L, item.path("payValue").asLong());
        assertEquals(9007199254740997L, item.path("actualValue").asLong());
        assertEquals("2027-01-01T10:00:00", item.path("beginTime").asText());
        assertEquals("2027-01-01T11:30:15", item.path("endTime").asText());
        verify(service).queryVoucherOfShop(Long.MAX_VALUE);
    }

    @Test
    void unknownShopKeepsSuccessfulEmptyArray() throws Exception {
        when(service.queryVoucherOfShop(404L)).thenReturn(Collections.emptyList());

        mvc.perform(get("/voucher/list/404")).andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true)).andExpect(jsonPath("$.data").isArray())
                .andExpect(jsonPath("$.data").isEmpty());

        verify(service).queryVoucherOfShop(404L);
    }

    @Test
    void anonymousAndAllRecognizedRolesCanReadWithoutMembershipChecks() throws Exception {
        when(service.queryVoucherOfShop(11L)).thenReturn(Collections.emptyList());
        mvc.perform(get("/voucher/list/11")).andExpect(status().isOk());
        for (String role : Arrays.asList("USER", "MERCHANT", "ADMIN")) {
            session(role, "ACTIVE", role);
            mvc.perform(get("/voucher/list/11").header("authorization", role)).andExpect(status().isOk());
        }
        verify(service, times(4)).queryVoucherOfShop(11L);
        verify(accounts, never()).countActiveMerchantMembership(anyLong(), anyLong());
    }

    @Test
    void expiredDisabledAndRolelessSessionsDoNotClosePublicRead() throws Exception {
        when(service.queryVoucherOfShop(12L)).thenReturn(Collections.emptyList());
        session("disabled", "DISABLED", "USER");
        session("roleless", "ACTIVE");
        when(redis.opsForHash()).thenReturn(hashes);
        when(hashes.entries(LOGIN_USER_KEY + "expired")).thenReturn(Collections.emptyMap());

        for (String token : Arrays.asList("disabled", "roleless", "expired")) {
            mvc.perform(get("/voucher/list/12").header("authorization", token)).andExpect(status().isOk());
        }

        verify(service, times(3)).queryVoucherOfShop(12L);
    }

    @Test
    void nonPositiveOrNonNumericShopIdsReturnValidation400() throws Exception {
        for (String shopId : Arrays.asList("0", "-1", "not-a-number")) {
            org.springframework.test.web.servlet.ResultActions response = mvc.perform(get("/voucher/list/" + shopId))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.success").value(false))
                    .andExpect(jsonPath("$.errorCode").value("VALIDATION_FAILED"));
            if (!"not-a-number".equals(shopId)) {
                response.andExpect(jsonPath("$.errorMsg").value("门店ID必须为正数"));
            }
        }
        verifyNoInteractions(service);
    }

    @Test
    void queryFailureBecomesGenericInternalError() throws Exception {
        when(service.queryVoucherOfShop(13L)).thenThrow(new IllegalStateException("synthetic private query detail"));

        String body = mvc.perform(get("/voucher/list/13")).andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.errorCode").value("INTERNAL_ERROR"))
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);

        assertFalse(body.contains("synthetic private query detail"));
    }

    private void session(String token, String accountStatus, String... roles) {
        long id = token.hashCode() & 0x7fffffffL;
        Map<Object, Object> identity = new HashMap<>();
        identity.put("id", Long.toString(id));
        identity.put("nickName", "synthetic");
        when(redis.opsForHash()).thenReturn(hashes);
        when(hashes.entries(LOGIN_USER_KEY + token)).thenReturn(identity);
        when(accounts.findAccountStatus(id)).thenReturn(accountStatus);
        when(accounts.findRoles(id)).thenReturn(Arrays.asList(roles));
    }
}
