package io.github.frewily.campushub.service;

import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.data.domain.Range;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.connection.stream.*;
import org.springframework.data.redis.core.RedisCallback;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.core.io.ClassPathResource;

import java.net.ServerSocket;
import java.nio.file.Path;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

/** Explicit IT, owns a non-persistent Redis; no shared developer keys are touched. */
class OrderStreamRedisIT {
    @TempDir static Path directory;
    private static Process process;
    private static LettuceConnectionFactory factory;
    private static StringRedisTemplate redis;
    private String stream;
    private final String group = "g1";

    @BeforeAll
    static void startRedis() throws Exception {
        int port;
        try (ServerSocket socket = new ServerSocket(0)) { port = socket.getLocalPort(); }
        process = new ProcessBuilder(System.getenv().getOrDefault("REDIS_SERVER_BINARY", "redis-server"),
                "--bind", "127.0.0.1", "--port", port + "", "--save", "", "--appendonly", "no",
                "--dir", directory.toString()).redirectErrorStream(true)
                .redirectOutput(directory.resolve("redis.log").toFile()).start();
        factory = new LettuceConnectionFactory("127.0.0.1", port);
        factory.afterPropertiesSet();
        redis = new StringRedisTemplate(factory);
        redis.afterPropertiesSet();
        boolean ready = false;
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (System.nanoTime() < deadline && process.isAlive()) {
            try {
                ready = "PONG".equals(redis.execute((RedisCallback<String>) connection -> connection.ping()));
                if (ready) break;
            } catch (RuntimeException unavailable) { Thread.sleep(20); }
        }
        assertTrue(ready, "isolated Redis failed to start");
    }

    @AfterAll
    static void stopRedis() throws Exception {
        if (factory != null) factory.destroy();
        if (process != null) {
            process.destroy();
            if (!process.waitFor(5, TimeUnit.SECONDS)) {
                process.destroyForcibly();
                assertTrue(process.waitFor(5, TimeUnit.SECONDS));
            }
        }
    }

    @BeforeEach
    void isolateStream() {
        stream = "stream:test:" + UUID.randomUUID();
        append("100");
        redis.opsForStream().createGroup(stream, ReadOffset.from("0-0"), group);
    }

    @Test
    void reproduceOldConsumerRestartLeavesPendingInvisibleToNewMessageRead() {
        List<MapRecord<String, Object, Object>> delivered = readNew("c1");
        assertEquals(1, delivered.size()); // process exits before ACK
        assertEquals(1L, redis.opsForStream().pending(stream, group).getTotalPendingMessages());
        assertTrue(readNew("c1").isEmpty()); // old startup only reads >, never reaches pending branch
        assertTrue(readNew("new-instance").isEmpty());
        assertTrue(redis.opsForStream().read(Consumer.from(group, "new-instance"),
                StreamReadOptions.empty().count(1), StreamOffset.create(stream, ReadOffset.from("0"))).isEmpty());
        List<MapRecord<String, Object, Object>> claimed = redis.opsForStream().claim(stream, group,
                "new-instance", Duration.ZERO, delivered.get(0).getId());
        assertEquals(delivered.get(0).getId(), claimed.get(0).getId());
        assertEquals("new-instance", redis.opsForStream().pending(stream, group, Range.unbounded(), 1)
                .get(0).getConsumerName());
    }

    @Test
    void newWorkerRecoversDeadConsumersWithoutAnyNewEvent() {
        MapRecord<String, Object, Object> record = readNew("dead-instance").get(0);
        OrderStreamQueue queue = queue("replacement", 3);
        queue.initialize(); // BUSYGROUP is idempotent
        assertEquals(record.getId(), queue.recoverPending().get(0).getId());
        assertEquals(1, queue.begin(record.getId()));
        assertEquals(1, queue.success(record.getId()));
        assertEquals(0L, redis.opsForStream().pending(stream, group).getTotalPendingMessages());
        assertFalse(redis.hasKey(stream + ".attempts"));
    }

    @Test
    void freshPendingIsNotStolenBeforeClaimIdle() {
        readNew("active");
        OrderStreamQueue queue = new OrderStreamQueue(redis, stream, group, "replacement", Duration.ofMinutes(1), 3);
        assertTrue(queue.recoverPending().isEmpty());
        assertEquals("active", redis.opsForStream().pending(stream, group, Range.unbounded(), 1).get(0).getConsumerName());
    }

    @Test
    void restartPreservesBudgetAndTerminalFailureArchivesExactlyOnceBeforeAck() {
        OrderStreamQueue first = queue("first", 2);
        MapRecord<String, Object, Object> record = first.readNew(Duration.ZERO).get(0);
        assertEquals(1, first.begin(record.getId()));
        assertEquals(0, first.failure(record.getId(), "DatabaseUnavailable"));
        assertEquals(1L, redis.opsForStream().pending(stream, group).getTotalPendingMessages());
        OrderStreamQueue replacement = queue("replacement", 2);
        assertEquals(record.getId(), replacement.recoverPending().get(0).getId());
        assertEquals(2, replacement.begin(record.getId()));
        assertEquals(1, replacement.failure(record.getId(), "DatabaseUnavailable"));
        assertEquals(-1, replacement.failure(record.getId(), "DatabaseUnavailable"));
        assertEquals(0L, redis.opsForStream().pending(stream, group).getTotalPendingMessages());
        assertEquals(1L, redis.opsForStream().size(stream + ".dead"));
        Map<Object, Object> dead = redis.opsForStream().range(stream + ".dead", Range.unbounded()).get(0).getValue();
        assertEquals("2", dead.get("attempts"));
        assertEquals("RETAINED", dead.get("reservation"));
        assertEquals("DatabaseUnavailable", dead.get("errorClass"));
        assertEquals(record.getId().getValue(), dead.get("sourceId"));
        assertNotNull(redis.opsForHash().get(stream + ".failures", record.getId().getValue()));
    }

    @Test
    void crashAfterLastBeginIsBoundedWithoutNeedingAnExceptionHandler() {
        OrderStreamQueue first = queue("first", 1);
        MapRecord<String, Object, Object> record = first.readNew(Duration.ZERO).get(0);
        assertEquals(1, first.begin(record.getId())); // process killed during/after DB call, before ACK
        OrderStreamQueue replacement = queue("replacement", 1);
        replacement.recoverPending();
        assertEquals(0, replacement.begin(record.getId()));
        assertEquals(1, replacement.failure(record.getId(), "RetryBudgetExhausted"));
        assertEquals(1L, redis.opsForStream().size(stream + ".dead"));
    }

    @Test
    void staleWorkerCannotAcknowledgeOrArchiveAfterOwnershipChanged() {
        OrderStreamQueue first = queue("first", 1);
        MapRecord<String, Object, Object> record = first.readNew(Duration.ZERO).get(0);
        assertEquals(1, first.begin(record.getId()));
        OrderStreamQueue other = queue("other", 1);
        other.recoverPending();
        assertEquals(-1, first.success(record.getId()));
        assertEquals(-1, first.failure(record.getId(), "LateFailure"));
        assertEquals(-1, first.begin(record.getId()));
        assertFalse(redis.hasKey(stream + ".dead"));
        assertEquals(1L, redis.opsForStream().pending(stream, group).getTotalPendingMessages());
    }

    @Test
    void deadLetterWriteFailureLeavesOriginalPendingAndAttemptsIntact() {
        OrderStreamQueue queue = queue("worker", 1);
        MapRecord<String, Object, Object> record = queue.readNew(Duration.ZERO).get(0);
        queue.begin(record.getId());
        redis.opsForValue().set(stream + ".dead", "wrong-type");
        assertThrows(RuntimeException.class, () -> queue.failure(record.getId(), "DatabaseUnavailable"));
        assertEquals(1L, redis.opsForStream().pending(stream, group).getTotalPendingMessages());
        assertEquals("1", redis.opsForHash().get(stream + ".attempts", record.getId().getValue()));
        redis.delete(stream + ".dead");
        // Also exercise an actual XADD failure after type preflight, not only WRONGTYPE.
        redis.execute((RedisCallback<Object>) connection -> connection.execute("XADD", bytes(stream + ".dead"),
                bytes("18446744073709551615-18446744073709551615"), bytes("marker"), bytes("test")));
        assertThrows(RuntimeException.class, () -> queue.failure(record.getId(), "DatabaseUnavailable"));
        assertEquals(1L, redis.opsForStream().pending(stream, group).getTotalPendingMessages());
        assertNull(redis.opsForHash().get(stream + ".failures", record.getId().getValue()));
    }

    @Test
    void brokenDeadLetterTypeDoesNotPreventAnotherOrderFromCompleting() {
        OrderStreamQueue queue = queue("worker", 1);
        MapRecord<String, Object, Object> record = queue.readNew(Duration.ZERO).get(0);
        redis.opsForValue().set(stream + ".dead", "wrong-type");
        assertEquals(1, queue.begin(record.getId()));
        assertThrows(RuntimeException.class, () -> queue.failure(record.getId(), "DatabaseUnavailable"));
        append("101");
        MapRecord<String, Object, Object> valid = queue.readNew(Duration.ZERO).get(0);
        assertEquals(1, queue.begin(valid.getId()));
        assertEquals(1, queue.success(valid.getId()));
        assertEquals(1L, redis.opsForStream().pending(stream, group).getTotalPendingMessages());
    }

    @Test
    void corruptAttemptCounterFailsClosedWithoutDroppingPending() {
        OrderStreamQueue queue = queue("worker", 3);
        MapRecord<String, Object, Object> record = queue.readNew(Duration.ZERO).get(0);
        redis.opsForHash().put(stream + ".attempts", record.getId().getValue(), "invalid");
        assertThrows(RuntimeException.class, () -> queue.begin(record.getId()));
        assertEquals(1L, redis.opsForStream().pending(stream, group).getTotalPendingMessages());
        assertFalse(redis.hasKey(stream + ".dead"));
    }

    @Test
    void pendingPaginationDoesNotStarveMessagesBehindAHotFirstPage() {
        for (int i = 0; i < 40; i++) append("200" + i);
        List<RecordId> ids = new ArrayList<>();
        for (int i = 0; i < 41; i++) ids.add(readNew("old").get(0).getId());
        for (int i = 32; i < 41; i++) {
            final String id = ids.get(i).getValue();
            redis.execute((RedisCallback<Object>) connection -> connection.execute("XCLAIM", bytes(stream),
                    bytes(group), bytes("old"), bytes("0"), bytes(id), bytes("IDLE"), bytes("120000")));
        }
        OrderStreamQueue queue = new OrderStreamQueue(redis, stream, group, "replacement", Duration.ofMinutes(1), 3);
        List<MapRecord<String, Object, Object>> first = queue.recoverPending();
        List<MapRecord<String, Object, Object>> second = queue.recoverPending();
        assertEquals(0, first.size()); // first page is hot, still advance cursor
        assertEquals(9, second.size());
        assertEquals(new HashSet<>(ids.subList(32, 41)), new HashSet<>(Arrays.asList(
                second.stream().map(MapRecord::getId).toArray(RecordId[]::new))));
    }

    @Test
    void redriveRetainsOrderIdAndIsIdempotentWithoutChangingReservedStock() {
        OrderStreamQueue queue = queue("worker", 1);
        MapRecord<String, Object, Object> record = queue.readNew(Duration.ZERO).get(0);
        // Replace the event for precision coverage; admission already tests reservation semantics.
        queue.success(record.getId());
        append("9007199254740993");
        record = queue.readNew(Duration.ZERO).get(0);
        queue.begin(record.getId());
        redis.opsForValue().set(stream + ".stock-sentinel", "4");
        queue.failure(record.getId(), "DatabaseUnavailable");
        String deadId = (String) redis.opsForHash().get(stream + ".failures", record.getId().getValue());
        String newId = redrive(deadId);
        assertEquals(newId, redrive(deadId));
        assertEquals("local-operator", redis.opsForHash().get(stream + ".redrives", deadId + ":operator"));
        assertEquals("DB_REVIEWED", redis.opsForHash().get(stream + ".redrives", deadId + ":reason"));
        assertNotNull(redis.opsForHash().get(stream + ".redrives", deadId + ":at"));
        MapRecord<String, Object, Object> replay = queue.readNew(Duration.ZERO).get(0);
        assertEquals(newId, replay.getId().getValue());
        assertEquals("9007199254740993", replay.getValue().get("id"));
        assertEquals("4", redis.opsForValue().get(stream + ".stock-sentinel"));
        assertEquals(1, queue.begin(replay.getId())); // explicit redrive grants new bounded budget
        assertEquals(1, queue.success(replay.getId()));
        assertEquals(1L, redis.opsForStream().size(stream + ".dead")); // audit retained
    }

    @Test
    void malformedPayloadCannotBeRedrivenAndDoesNotBlockValidNewWork() {
        OrderStreamQueue queue = queue("worker", 1);
        MapRecord<String, Object, Object> valid = queue.readNew(Duration.ZERO).get(0);
        queue.success(valid.getId());
        append("9223372036854775808");
        MapRecord<String, Object, Object> malformed = queue.readNew(Duration.ZERO).get(0);
        queue.begin(malformed.getId());
        queue.failure(malformed.getId(), "NumberFormatException");
        String deadId = (String) redis.opsForHash().get(stream + ".failures", malformed.getId().getValue());
        assertThrows(RuntimeException.class, () -> redrive(deadId));
        append("101");
        assertEquals("101", queue.readNew(Duration.ZERO).get(0).getValue().get("id"));
        assertFalse(redis.hasKey(stream + ".redrives"));
    }

    @Test
    void startsSpringWorkerAndRecoversAnIdleEventWithoutNewTraffic() throws Exception {
        MapRecord<String, Object, Object> record = readNew("dead-instance").get(0);
        redis.execute((RedisCallback<Object>) connection -> connection.execute("XCLAIM", bytes(stream),
                bytes(group), bytes("dead-instance"), bytes("0"), bytes(record.getId().getValue()),
                bytes("IDLE"), bytes("2000")));
        // Production stream name is fixed; use this process's unused stream.orders for the worker test.
        redis.rename(stream, "stream.orders");
        IVoucherOrderService orders = org.mockito.Mockito.mock(IVoucherOrderService.class);
        org.springframework.context.annotation.AnnotationConfigApplicationContext context =
                new org.springframework.context.annotation.AnnotationConfigApplicationContext();
        context.getEnvironment().getPropertySources().addFirst(new org.springframework.core.env.MapPropertySource(
                "test-policy", Collections.singletonMap("campushub.order.claim-idle-ms", 1000)));
        context.registerBean(IVoucherOrderService.class, () -> orders);
        context.registerBean(StringRedisTemplate.class, () -> redis);
        context.register(OrderStreamConsumer.class);
        try {
            context.refresh();
            org.mockito.Mockito.verify(orders, org.mockito.Mockito.timeout(5000)).handleVoucherOrder(
                    org.mockito.ArgumentMatchers.argThat(order -> order.getId().equals(100L)));
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3);
            while (redis.opsForStream().pending("stream.orders", group).getTotalPendingMessages() != 0
                    && System.nanoTime() < deadline) Thread.sleep(20);
            assertEquals(0L, redis.opsForStream().pending("stream.orders", group).getTotalPendingMessages());
        } finally {
            context.close();
            // Reset only keys owned by this isolated process; other IT cases use UUID stream keys.
            redis.delete(Arrays.asList("stream.orders", "stream.orders.attempts"));
        }
    }

    @Test
    void initializeCreatesAnEmptyStreamGroupAndReadsFromTheBeginning() {
        String empty = "stream:empty:" + UUID.randomUUID();
        OrderStreamQueue queue = new OrderStreamQueue(redis, empty, group, "worker", Duration.ZERO, 3);
        queue.initialize();
        queue.initialize();
        assertEquals(0L, redis.opsForStream().size(empty));
        redis.opsForStream().add(empty, Collections.singletonMap("marker", "test"));
        assertEquals(1, queue.readNew(Duration.ZERO).size());
    }

    @Test
    void archiveIndexWithoutAnActualDeadLetterCannotCauseAck() {
        OrderStreamQueue queue = queue("worker", 1);
        MapRecord<String, Object, Object> record = queue.readNew(Duration.ZERO).get(0);
        queue.begin(record.getId());
        redis.opsForHash().put(stream + ".failures", record.getId().getValue(), "1-0");
        assertThrows(RuntimeException.class, () -> queue.failure(record.getId(), "DatabaseUnavailable"));
        assertEquals(1L, redis.opsForStream().pending(stream, group).getTotalPendingMessages());
    }

    @Test
    void redriveAppendFailureDoesNotMarkFailureAsRedriven() {
        OrderStreamQueue queue = queue("worker", 1);
        MapRecord<String, Object, Object> record = queue.readNew(Duration.ZERO).get(0);
        queue.begin(record.getId());
        queue.failure(record.getId(), "DatabaseUnavailable");
        String deadId = (String) redis.opsForHash().get(stream + ".failures", record.getId().getValue());
        redis.execute((RedisCallback<Object>) connection -> connection.execute("XADD", bytes(stream),
                bytes("18446744073709551615-18446744073709551615"), bytes("marker"), bytes("test")));
        assertThrows(RuntimeException.class, () -> redrive(deadId));
        assertFalse(redis.hasKey(stream + ".redrives"));
        assertEquals(1L, redis.opsForStream().size(stream + ".dead"));
    }

    private OrderStreamQueue queue(String consumer, int budget) {
        return new OrderStreamQueue(redis, stream, group, consumer, Duration.ZERO, budget);
    }

    private String redrive(String deadId) {
        DefaultRedisScript<String> script = new DefaultRedisScript<>();
        script.setLocation(new ClassPathResource("redrive-order.lua"));
        script.setResultType(String.class);
        return redis.execute(script, Arrays.asList(stream, stream + ".dead", stream + ".redrives"),
                deadId, "local-operator", "DB_REVIEWED");
    }

    private byte[] bytes(String value) { return value.getBytes(java.nio.charset.StandardCharsets.UTF_8); }

    private RecordId append(String orderId) {
        Map<String, String> fields = new LinkedHashMap<>();
        fields.put("id", orderId);
        fields.put("userId", "7");
        fields.put("voucherId", "9");
        return redis.opsForStream().add(stream, fields);
    }

    private List<MapRecord<String, Object, Object>> readNew(String consumer) {
        return redis.opsForStream().read(Consumer.from(group, consumer), StreamReadOptions.empty().count(1),
                StreamOffset.create(stream, ReadOffset.lastConsumed()));
    }
}
