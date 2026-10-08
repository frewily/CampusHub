package io.github.frewily.campushub.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.frewily.campushub.dto.UserDTO;
import io.github.frewily.campushub.entity.*;
import io.github.frewily.campushub.exception.*;
import io.github.frewily.campushub.mapper.*;
import io.github.frewily.campushub.security.ResourceAuthorizationService;
import io.github.frewily.campushub.utils.UserHolder;
import org.junit.jupiter.api.*;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.data.redis.core.*;
import org.springframework.data.redis.connection.stream.*;
import org.springframework.transaction.support.*;

import java.time.LocalDateTime;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

class OrderLifecycleServiceTest {
    private final VoucherOrderMapper orders = mock(VoucherOrderMapper.class);
    private final VoucherMapper vouchers = mock(VoucherMapper.class);
    private final OrderCancellationMapper cancellations = mock(OrderCancellationMapper.class);
    private final AccountAccessMapper accounts = mock(AccountAccessMapper.class);
    private final ResourceAuthorizationService authorization = mock(ResourceAuthorizationService.class);
    private final TransactionTemplate tx = mock(TransactionTemplate.class);
    private final StringRedisTemplate redis = mock(StringRedisTemplate.class);
    private final HashOperations<String, Object, Object> hashes = mock(HashOperations.class);
    private final StreamOperations<String, Object, Object> streams = mock(StreamOperations.class);
    private final OrderLifecycleService service = new OrderLifecycleService(orders, vouchers, cancellations,
            accounts, authorization, tx, redis, new ObjectMapper());

    @BeforeEach
    void session() {
        UserDTO user = new UserDTO();
        user.setId(7L);
        UserHolder.saveUser(user);
        when(authorization.canParticipateAsUser()).thenReturn(true);
        when(accounts.findAccountStatus(7L)).thenReturn("ACTIVE");
        when(redis.opsForHash()).thenReturn(hashes);
        when(redis.opsForStream()).thenReturn(streams);
        doAnswer(invocation -> ((TransactionCallback<?>) invocation.getArgument(0))
                .doInTransaction(mock(org.springframework.transaction.TransactionStatus.class))).when(tx).execute(any());
    }

    @AfterEach void clear() { UserHolder.removeUser(); }

    @Test
    void persistedOrderWinsOverRedisAndRetainsStringIdentifiers() {
        when(orders.findOwned(100L, 7L, 9L)).thenReturn(order(1));
        assertEquals("PENDING_PAYMENT", service.queryMine(100L, 9L).getStatus());
        assertEquals("100", service.queryMine(100L, 9L).getOrderId());
        verifyNoInteractions(hashes, streams);
    }

    @Test
    void acceptedReceiptIsOnlyAProcessingObservation() {
        when(hashes.get("seckill:request:9", "7")).thenReturn("100");
        assertEquals("ACCEPTED", service.queryMine(100L, 9L).getStatus());
        verifyNoInteractions(vouchers, cancellations);
    }

    @Test
    void unknownOrOtherOwnersOrderDoesNotExposeFailureRecords() {
        assertCode(ErrorCode.NOT_FOUND, () -> service.queryMine(100L, 9L));
        verify(hashes, never()).get(eq("stream.orders.failures"), any());
    }

    @Test
    void archivedFailureAndConfirmedRedriveHaveDifferentObservations() {
        failureReceipt("[\"id\",\"100\",\"userId\",\"7\",\"voucherId\",\"9\"]");
        assertEquals("REQUIRES_REVIEW", service.queryMine(100L, 9L).getStatus());
        when(hashes.get("stream.orders.redrives", "1-0")).thenReturn("2-0");
        assertEquals("ACCEPTED", service.queryMine(100L, 9L).getStatus());
    }

    @Test
    void foreignOrCorruptDlqIndexCannotBeReportedAsOwnFailure() {
        failureReceipt("[\"id\",\"100\",\"userId\",\"8\",\"voucherId\",\"9\"]");
        assertCode(ErrorCode.ORDER_STATE_UNAVAILABLE, () -> service.queryMine(100L, 9L));
        failureReceipt("not-json");
        assertCode(ErrorCode.ORDER_STATE_UNAVAILABLE, () -> service.queryMine(100L, 9L));
    }

    @Test
    void missingArchivedPayloadFailsClosed() {
        when(hashes.get("seckill:request:9", "7")).thenReturn("100");
        when(hashes.get("stream.orders.failures", "order:100")).thenReturn("1-0");
        when(streams.range(eq("stream.orders.dead"), any())).thenReturn(Collections.emptyList());
        assertCode(ErrorCode.ORDER_STATE_UNAVAILABLE, () -> service.queryMine(100L, 9L));
    }

    @Test
    void cancelledLegacyRowIsExplicitlyUntracked() {
        when(orders.findOwned(100L, 7L, 9L)).thenReturn(order(4));
        assertEquals("UNTRACKED", service.queryMine(100L, 9L).getCancellationCompensation());
    }

    @Test
    void unknownStoredStateIsNotGuessed() {
        when(orders.findOwned(100L, 7L, 9L)).thenReturn(order(99));
        assertCode(ErrorCode.ORDER_STATE_UNAVAILABLE, () -> service.queryMine(100L, 9L));
    }

    @Test
    void unknownCompensationStateIsNotExposedAsPublicStatus() {
        when(orders.findOwned(100L, 7L, 9L)).thenReturn(order(4));
        when(cancellations.find(100L)).thenReturn(new OrderCancellation().setStatus("corrupt"));
        assertCode(ErrorCode.ORDER_STATE_UNAVAILABLE, () -> service.queryMine(100L, 9L));
    }

    @Test
    void applicationAccountLookupFailureIsUncertainNotDisabled() {
        when(accounts.findAccountStatus(7L)).thenThrow(new DataAccessResourceFailureException("synthetic outage"));
        assertCode(ErrorCode.ORDER_STATE_UNAVAILABLE, () -> service.queryMine(100L, 9L));
        verifyNoInteractions(orders);
    }

    @Test
    void databaseFailureIsUncertainNotNotFound() {
        when(orders.findOwned(anyLong(), anyLong(), anyLong()))
                .thenThrow(new DataAccessResourceFailureException("must not expose this message"));
        assertCode(ErrorCode.ORDER_STATE_UNAVAILABLE, () -> service.queryMine(100L, 9L));
        verifyNoInteractions(hashes);
    }

    @Test
    void cancelWritesOrderStockAndOutboxInOneCallbackWithoutRedisWrite() {
        prepareCancellation();
        assertEquals("PENDING", service.cancelMine(100L, 9L).getCancellationCompensation());
        org.mockito.InOrder writes = inOrder(orders, cancellations);
        writes.verify(orders).lockOwned(100L, 7L, 9L);
        writes.verify(orders).cancelUnpaid(100L, 7L, 9L);
        writes.verify(orders).returnStock(9L);
        writes.verify(cancellations).insert(argThat(entry -> entry.getOrderId().equals(100L)
                && entry.getUserId().equals(7L) && entry.getVoucherId().equals(9L) && entry.getExpiresAtMs() > 0));
        verifyNoInteractions(hashes, streams);
    }

    @Test
    void repeatedCancellationDoesNotReturnStockAgain() {
        when(orders.lockOwned(100L, 7L, 9L)).thenReturn(order(4));
        when(cancellations.find(100L)).thenReturn(new OrderCancellation().setStatus("COMPLETED"));
        assertEquals("COMPLETED", service.cancelMine(100L, 9L).getCancellationCompensation());
        verify(orders, never()).returnStock(anyLong());
        verify(cancellations, never()).insert(any());
    }

    @Test
    void paidRedeemedAndRefundStatesCannotBeCancelledAsUnpaid() {
        for (int status : Arrays.asList(2, 3, 5, 6, 99)) {
            when(orders.lockOwned(100L, 7L, 9L)).thenReturn(order(status));
            assertCode(ErrorCode.CONFLICT, () -> service.cancelMine(100L, 9L));
        }
        verifyNoInteractions(vouchers, cancellations);
    }

    @Test
    void acceptedButUnpersistedCannotReleaseInventory() {
        when(hashes.get("seckill:request:9", "7")).thenReturn("100");
        assertCode(ErrorCode.CONFLICT, () -> service.cancelMine(100L, 9L));
        verify(orders, never()).returnStock(anyLong());
        verifyNoInteractions(cancellations);
    }

    @Test
    void stockFailureThrowsAndPreventsOutboxCreation() {
        prepareCancellation();
        when(orders.returnStock(9L)).thenReturn(0);
        assertCode(ErrorCode.ORDER_STATE_UNAVAILABLE, () -> service.cancelMine(100L, 9L));
        verify(cancellations, never()).insert(any());
    }

    @Test
    void outboxInsertFailureMustPropagateToTransactionRollback() {
        prepareCancellation();
        when(cancellations.insert(any())).thenReturn(0);
        assertCode(ErrorCode.ORDER_STATE_UNAVAILABLE, () -> service.cancelMine(100L, 9L));
    }

    @Test
    void anonymousDisabledAdminAndInvalidIdentifiersFailBeforeDataAccess() {
        UserHolder.removeUser();
        assertCode(ErrorCode.AUTHENTICATION_FAILED, () -> service.queryMine(100L, 9L));
        session();
        when(accounts.findAccountStatus(7L)).thenReturn("DISABLED");
        assertCode(ErrorCode.AUTHORIZATION_FAILED, () -> service.cancelMine(100L, 9L));
        when(authorization.canParticipateAsUser()).thenReturn(false);
        assertCode(ErrorCode.AUTHORIZATION_FAILED, () -> service.queryMine(100L, 9L));
        assertCode(ErrorCode.VALIDATION_FAILED, () -> service.cancelMine(-1L, 9L));
        verifyNoInteractions(orders);
    }

    private void prepareCancellation() {
        when(orders.lockOwned(100L, 7L, 9L)).thenReturn(order(1));
        when(vouchers.findFlashSale(9L)).thenReturn(new Voucher().setId(9L).setType(1)
                .setBeginTime(LocalDateTime.of(2026, 10, 8, 0, 0)).setEndTime(LocalDateTime.of(2026, 10, 9, 0, 0)));
        when(orders.cancelUnpaid(100L, 7L, 9L)).thenReturn(1);
        when(orders.returnStock(9L)).thenReturn(1);
        when(cancellations.insert(any())).thenReturn(1);
        when(orders.findOwned(100L, 7L, 9L)).thenReturn(order(4));
        when(cancellations.find(100L)).thenReturn(new OrderCancellation().setStatus("PENDING"));
    }

    private void failureReceipt(String payload) {
        when(hashes.get("seckill:request:9", "7")).thenReturn("100");
        when(hashes.get("stream.orders.failures", "order:100")).thenReturn("1-0");
        Map<Object, Object> fields = Collections.singletonMap("payload", payload);
        when(streams.range(eq("stream.orders.dead"), any())).thenReturn(Collections.singletonList(
                StreamRecords.newRecord().in("stream.orders.dead").ofMap(fields).withId(RecordId.of("1-0"))));
    }

    private VoucherOrder order(int status) { return new VoucherOrder().setId(100L).setUserId(7L).setVoucherId(9L).setStatus(status); }
    private void assertCode(ErrorCode code, Runnable action) {
        assertEquals(code, assertThrows(BusinessException.class, action::run).getErrorCode());
    }
}
