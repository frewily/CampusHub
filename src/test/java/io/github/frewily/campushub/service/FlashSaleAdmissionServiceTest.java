package io.github.frewily.campushub.service;

import io.github.frewily.campushub.dto.UserDTO;
import io.github.frewily.campushub.dto.response.OrderAcceptanceResult;
import io.github.frewily.campushub.entity.Voucher;
import io.github.frewily.campushub.exception.BusinessException;
import io.github.frewily.campushub.exception.ErrorCode;
import io.github.frewily.campushub.mapper.AccountAccessMapper;
import io.github.frewily.campushub.mapper.VoucherMapper;
import io.github.frewily.campushub.security.ResourceAuthorizationService;
import io.github.frewily.campushub.utils.RedisIdWorker;
import io.github.frewily.campushub.utils.UserHolder;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;

import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class FlashSaleAdmissionServiceTest {
    @Mock VoucherMapper vouchers;
    @Mock AccountAccessMapper accounts;
    @Mock ResourceAuthorizationService authorization;
    @Mock RedisIdWorker idWorker;
    @Mock StringRedisTemplate redis;
    private FlashSaleAdmissionService service;

    @BeforeEach
    void setUp() {
        service = new FlashSaleAdmissionService(vouchers, accounts, authorization, idWorker, redis);
        UserDTO user = new UserDTO();
        user.setId(7L);
        UserHolder.saveUser(user);
    }

    @AfterEach
    void clearUser() { UserHolder.removeUser(); }

    @ParameterizedTest
    @CsvSource({"0,false", "9,true"})
    void acceptedAndReplayedResponsesPreserveOriginalLongId(long code, boolean replayed) {
        ready();
        when(execute()).thenReturn(Arrays.asList(code, "9007199254740993"));
        OrderAcceptanceResult result = (OrderAcceptanceResult) service.admit(9L);
        assertEquals(9007199254740993L, result.getData());
        assertEquals("ACCEPTED", result.getAcceptanceStatus());
        assertEquals(replayed, result.isReplayed());
    }

    @ParameterizedTest
    @CsvSource({"1,SOLD_OUT", "2,ALREADY_PARTICIPATED", "4,ACTIVITY_NOT_STARTED",
            "5,ACTIVITY_ENDED", "6,ACTIVITY_INACTIVE", "7,AUTHORIZATION_FAILED",
            "8,ACTIVITY_UNAVAILABLE", "123,ACTIVITY_UNAVAILABLE"})
    void everyLuaRejectionHasAnExplicitError(long code, ErrorCode expected) {
        ready();
        when(execute()).thenReturn(Arrays.asList(code, ""));
        assertEquals(expected, assertThrows(BusinessException.class, () -> service.admit(9L)).getErrorCode());
    }

    @Test
    void nullMalformedAndInvalidIdRepliesFailClosed() {
        ready();
        when(execute()).thenReturn(null, Collections.singletonList(0L),
                Arrays.asList("0", "100"), Arrays.asList(0L, "not-an-id"), Arrays.asList(9L, "-1"));
        for (int i = 0; i < 5; i++) {
            assertEquals(ErrorCode.ACTIVITY_UNAVAILABLE,
                    assertThrows(BusinessException.class, () -> service.admit(9L)).getErrorCode());
        }
    }

    @Test
    void redisTimeoutDoesNotClaimThatReservationWasRejected() {
        ready();
        when(execute()).thenThrow(new RedisConnectionFailureException("timeout"));
        BusinessException error = assertThrows(BusinessException.class, () -> service.admit(9L));
        assertEquals(ErrorCode.ACTIVITY_UNAVAILABLE, error.getErrorCode());
        assertEquals(503, error.getErrorCode().getHttpStatus().value());
    }

    @Test
    void idAllocationFailureUsesSameRetryableError() {
        authorize();
        when(vouchers.findFlashSale(9L)).thenReturn(voucher());
        when(idWorker.nextId("order")).thenThrow(new RedisConnectionFailureException("timeout"));
        assertEquals(ErrorCode.ACTIVITY_UNAVAILABLE,
                assertThrows(BusinessException.class, () -> service.admit(9L)).getErrorCode());
        verifyNoInteractions(redis);
    }

    @Test
    void anonymousOrInvalidInputNeverTouchesInventory() {
        UserHolder.removeUser();
        assertEquals(ErrorCode.AUTHENTICATION_FAILED,
                assertThrows(BusinessException.class, () -> service.admit(9L)).getErrorCode());
        assertEquals(ErrorCode.VALIDATION_FAILED,
                assertThrows(BusinessException.class, () -> service.admit(0L)).getErrorCode());
        verifyNoInteractions(vouchers, accounts, authorization, redis, idWorker);
    }

    @Test
    void ineligibleRoleAndDisabledAccountNeverReachLua() {
        when(authorization.canParticipateAsUser()).thenReturn(false, true);
        assertEquals(ErrorCode.AUTHORIZATION_FAILED,
                assertThrows(BusinessException.class, () -> service.admit(9L)).getErrorCode());
        when(accounts.findAccountStatus(7L)).thenReturn("DISABLED");
        assertEquals(ErrorCode.AUTHORIZATION_FAILED,
                assertThrows(BusinessException.class, () -> service.admit(9L)).getErrorCode());
        verifyNoInteractions(vouchers, redis, idWorker);
    }

    @Test
    void missingOrOrdinaryVoucherIsNotAFlashSale() {
        authorize();
        when(vouchers.findFlashSale(9L)).thenReturn(null, new Voucher().setType(0));
        for (int i = 0; i < 2; i++) {
            assertEquals(ErrorCode.NOT_FOUND,
                    assertThrows(BusinessException.class, () -> service.admit(9L)).getErrorCode());
        }
        verifyNoInteractions(redis, idWorker);
    }

    private void authorize() {
        when(authorization.canParticipateAsUser()).thenReturn(true);
        when(accounts.findAccountStatus(7L)).thenReturn("ACTIVE");
    }

    private void ready() {
        authorize();
        when(vouchers.findFlashSale(9L)).thenReturn(voucher());
        when(idWorker.nextId("order")).thenReturn(100L);
    }

    @SuppressWarnings("unchecked")
    private List execute() {
        return redis.execute(any(RedisScript.class), anyList(), eq("7"), eq("100"), eq("1"), eq("1"),
                anyString(), anyString(), eq("9"));
    }

    static Voucher voucher() {
        return new Voucher().setId(9L).setType(1).setStatus(1).setStock(3)
                .setBeginTime(LocalDateTime.of(2027, 1, 1, 10, 0))
                .setEndTime(LocalDateTime.of(2027, 1, 1, 11, 0));
    }
}
