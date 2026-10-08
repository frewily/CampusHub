package io.github.frewily.campushub.service;

import io.github.frewily.campushub.entity.VoucherOrder;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.connection.stream.*;

import java.time.Duration;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class OrderStreamConsumerTest {
    private final IVoucherOrderService orders = mock(IVoucherOrderService.class);
    private final OrderStreamQueue queue = mock(OrderStreamQueue.class);
    private final OrderStreamConsumer consumer = new OrderStreamConsumer(orders, queue);

    @Test
    void recoveryRunsWithoutANewMessageOrEarlierException() {
        MapRecord<String, Object, Object> record = record("100");
        when(queue.recoverPending()).thenReturn(Collections.singletonList(record));
        when(queue.readNew(any(Duration.class))).thenReturn(Collections.emptyList());
        when(queue.begin(record.getId())).thenReturn(1L);
        consumer.consumeOnce();
        verify(orders).handleVoucherOrder(argThat(order -> order.getId().equals(100L)));
        verify(queue).success(record.getId());
        verify(queue).readNew(any(Duration.class));
    }

    @Test
    void poisonRecordDoesNotPreventNextNewRecord() {
        MapRecord<String, Object, Object> poison = record("not-an-id");
        MapRecord<String, Object, Object> valid = record("100").withId(RecordId.of("2-0"));
        when(queue.recoverPending()).thenReturn(Collections.singletonList(poison));
        when(queue.readNew(any(Duration.class))).thenReturn(Collections.singletonList(valid));
        when(queue.begin(any())).thenReturn(1L);
        consumer.consumeOnce();
        verify(queue).failure(poison.getId(), "IllegalArgumentException");
        verify(queue, never()).success(poison.getId());
        verify(queue).success(valid.getId());
        verify(orders).handleVoucherOrder(any(VoucherOrder.class));
    }

    @Test
    void persistenceFailureIsNeverSuccessAcknowledged() {
        MapRecord<String, Object, Object> record = record("100");
        when(queue.begin(record.getId())).thenReturn(1L);
        doThrow(new IllegalStateException("sensitive database message must not be stored"))
                .when(orders).handleVoucherOrder(any());
        consumer.process(record);
        verify(queue).failure(record.getId(), "IllegalStateException");
        verify(queue, never()).success(any());
    }

    @Test
    void archivalInfrastructureFailureDoesNotStarveOtherRecords() {
        MapRecord<String, Object, Object> poison = record("not-an-id");
        MapRecord<String, Object, Object> valid = record("100").withId(RecordId.of("2-0"));
        when(queue.recoverPending()).thenReturn(Collections.singletonList(poison));
        when(queue.readNew(any(Duration.class))).thenReturn(Collections.singletonList(valid));
        when(queue.begin(any())).thenReturn(1L);
        when(queue.failure(poison.getId(), "IllegalArgumentException"))
                .thenThrow(new IllegalStateException("dead-letter store unavailable"));
        consumer.consumeOnce();
        verify(queue, never()).success(poison.getId());
        verify(queue).success(valid.getId());
    }

    @Test
    void acknowledgementFailureIsNotMistakenForPersistenceFailure() {
        MapRecord<String, Object, Object> record = record("100");
        when(queue.begin(record.getId())).thenReturn(1L);
        when(queue.success(record.getId())).thenThrow(new IllegalStateException("ACK unavailable"));
        assertThrows(IllegalStateException.class, () -> consumer.process(record));
        verify(orders).handleVoucherOrder(any());
        verify(queue, never()).failure(any(), anyString());
    }

    @Test
    void exhaustedCrashBudgetArchivesWithoutAnotherDatabaseWrite() {
        MapRecord<String, Object, Object> record = record("100");
        when(queue.begin(record.getId())).thenReturn(0L);
        consumer.process(record);
        verifyNoInteractions(orders);
        verify(queue).failure(record.getId(), "RetryBudgetExhausted");
    }

    @Test
    void staleOwnerDoesNotWriteOrAcknowledge() {
        MapRecord<String, Object, Object> record = record("100");
        when(queue.begin(record.getId())).thenReturn(-1L);
        consumer.process(record);
        verifyNoInteractions(orders);
        verify(queue, never()).success(any());
        verify(queue, never()).failure(any(), anyString());
    }

    @Test
    void businessFailureStoresReasonCodeRatherThanExceptionText() {
        MapRecord<String, Object, Object> record = record("100");
        when(queue.begin(record.getId())).thenReturn(1L);
        doThrow(new io.github.frewily.campushub.exception.OrderProcessingException(
                io.github.frewily.campushub.exception.OrderProcessingException.Reason.LOCK_BUSY))
                .when(orders).handleVoucherOrder(any());
        consumer.process(record);
        verify(queue).failure(record.getId(), "LOCK_BUSY");
    }

    @Test
    void identifiersRemain64BitStringsAndRejectMalformedEvents() {
        assertEquals(9007199254740993L, OrderStreamConsumer.decode(record("9007199254740993").getValue()).getId());
        for (String invalid : Arrays.asList("0", "-1", "01", "1.0", "9223372036854775808", "")) {
            assertThrows(IllegalArgumentException.class, () -> OrderStreamConsumer.decode(record(invalid).getValue()));
        }
        Map<Object, Object> fields = new HashMap<>(record("100").getValue());
        fields.put("status", "paid");
        assertThrows(IllegalArgumentException.class, () -> OrderStreamConsumer.decode(fields));
        fields.remove("status");
        fields.remove("userId");
        assertThrows(IllegalArgumentException.class, () -> OrderStreamConsumer.decode(fields));
    }

    @Test
    void eachProcessHasUniqueConsumerIdentityAndRejectsUnsafePolicy() {
        org.springframework.data.redis.core.StringRedisTemplate redis = mock(org.springframework.data.redis.core.StringRedisTemplate.class);
        OrderStreamConsumer first = new OrderStreamConsumer(orders, redis, false, 60000, 5);
        OrderStreamConsumer second = new OrderStreamConsumer(orders, redis, false, 60000, 5);
        Object a = org.springframework.test.util.ReflectionTestUtils.getField(first, "consumerName");
        Object b = org.springframework.test.util.ReflectionTestUtils.getField(second, "consumerName");
        assertNotEquals(a, b);
        assertThrows(IllegalArgumentException.class, () -> new OrderStreamConsumer(orders, redis, true, 0, 5));
        assertThrows(IllegalArgumentException.class, () -> new OrderStreamConsumer(orders, redis, true, 60000, 0));
        first.stop();
        second.stop();
    }

    private MapRecord<String, Object, Object> record(String id) {
        Map<Object, Object> fields = new LinkedHashMap<>();
        fields.put("id", id);
        fields.put("userId", "7");
        fields.put("voucherId", "9");
        return StreamRecords.newRecord().in("stream.orders").ofMap(fields).withId(RecordId.of("1-0"));
    }
}
