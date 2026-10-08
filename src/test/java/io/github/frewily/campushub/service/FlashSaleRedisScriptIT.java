package io.github.frewily.campushub.service;

import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;

import java.net.ServerSocket;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.*;

import static org.junit.jupiter.api.Assertions.*;

/** Explicit -Dtest=FlashSaleRedisScriptIT; owns an isolated, non-persistent Redis process. */
class FlashSaleRedisScriptIT {
    @TempDir static Path directory;
    private static Process process;
    private static LettuceConnectionFactory factory;
    private static StringRedisTemplate redis;
    private static final DefaultRedisScript<Long> INITIALIZE = new DefaultRedisScript<>();
    private static final DefaultRedisScript<List> ADMIT = new DefaultRedisScript<>();
    private List<String> keys;
    private long begin;
    private long end;

    @BeforeAll
    static void startRedis() throws Exception {
        int port;
        try (ServerSocket socket = new ServerSocket(0)) { port = socket.getLocalPort(); }
        String binary = System.getenv().getOrDefault("REDIS_SERVER_BINARY", "redis-server");
        process = new ProcessBuilder(binary, "--bind", "127.0.0.1", "--port", port + "",
                "--save", "", "--appendonly", "no", "--dir", directory.toString())
                .redirectErrorStream(true).redirectOutput(directory.resolve("redis.log").toFile()).start();
        factory = new LettuceConnectionFactory("127.0.0.1", port);
        factory.afterPropertiesSet();
        redis = new StringRedisTemplate(factory);
        redis.afterPropertiesSet();
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        boolean ready = false;
        while (System.nanoTime() < deadline && process.isAlive()) {
            try {
                ready = "PONG".equals(redis.execute((org.springframework.data.redis.core.RedisCallback<String>)
                        connection -> connection.ping()));
                if (ready) break;
            } catch (RuntimeException unavailable) { Thread.sleep(20); }
        }
        assertTrue(ready, "isolated Redis failed to start");
        INITIALIZE.setLocation(new ClassPathResource("initialize-flash-sale.lua"));
        INITIALIZE.setResultType(Long.class);
        ADMIT.setLocation(new ClassPathResource("seckill.lua"));
        ADMIT.setResultType(List.class);
    }

    @AfterAll
    static void stopRedis() throws Exception {
        if (factory != null) factory.destroy();
        if (process != null) {
            process.destroy();
            if (!process.waitFor(5, TimeUnit.SECONDS)) {
                process.destroyForcibly();
                assertTrue(process.waitFor(5, TimeUnit.SECONDS), "test-owned Redis did not exit");
            }
        }
    }

    @BeforeEach
    void activity() {
        // All keys live only in this test-owned process, never in a shared developer Redis.
        keys = new ArrayList<>(FlashSaleRules.activityKeys(System.nanoTime()));
        keys.add("stream:test:" + UUID.randomUUID());
        begin = System.currentTimeMillis() - 60000;
        end = System.currentTimeMillis() + 60000;
        assertEquals(0L, initialize(3));
    }

    @Test
    void admissionAndRetryPreserve64BitIdsEvenWhenSoldOutOrEnded() {
        assertEquals(Arrays.asList(0L, "9007199254740993"), admit("7", "9007199254740993", "1", "1"));
        redis.opsForValue().set(keys.get(0), "0");
        assertEquals(Arrays.asList(9L, "9007199254740993"), admit("7", "9007199254740995", "1", "1"));
        end = System.currentTimeMillis() - 10000;
        metadata("endAt", end + "");
        metadata("expireAt", (end + 86400000) + "");
        assertEquals(Arrays.asList(9L, "9007199254740993"), admit("7", "9007199254740997", "1", "2"));
        assertEquals(1L, redis.opsForStream().size(keys.get(4)));
        assertEquals("0", redis.opsForValue().get(keys.get(0)));
    }

    @Test
    void timeStatusAndQualificationRejectBeforeStockMutation() {
        begin = System.currentTimeMillis() + 30000;
        metadata("beginAt", begin + "");
        assertEquals(4L, code(admit("7", "100", "1", "1")));
        begin = System.currentTimeMillis() - 60000;
        end = System.currentTimeMillis() - 30000;
        metadata("beginAt", begin + "");
        metadata("endAt", end + "");
        metadata("expireAt", (end + 86400000) + "");
        assertEquals(5L, code(admit("7", "100", "1", "1")));
        assertEquals(6L, code(admit("7", "100", "1", "2")));
        assertEquals(7L, code(admit("7", "100", "0", "1")));
        assertEquals(5L, code(admit("7", "100", "1", "3")));
        assertUnmodified();
    }

    @Test
    void absentStaleAndMalformedRulesFailClosed() {
        metadata("beginAt", "1");
        assertEquals(8L, code(admit("7", "100", "1", "1")));
        metadata("beginAt", begin + "");
        redis.opsForValue().set(keys.get(0), "3.0");
        assertEquals(8L, code(admit("7", "100", "1", "1")));
        redis.opsForValue().set(keys.get(0), "3");
        metadata("expireAt", "1.5");
        assertEquals(8L, code(admit("7", "100", "1", "1")));
        redis.delete(keys.get(2));
        assertEquals(8L, code(admit("7", "100", "1", "1")));
        assertUnmodified();
    }

    @Test
    void wrongStreamTypeDoesNotConsumeStockOrRecordParticipation() {
        redis.opsForValue().set(keys.get(4), "not-a-stream");
        assertEquals(8L, code(admit("7", "100", "1", "1")));
        assertEquals("3", redis.opsForValue().get(keys.get(0)));
        assertFalse(redis.hasKey(keys.get(1)));
        assertFalse(redis.hasKey(keys.get(3)));
        assertEquals("not-a-stream", redis.opsForValue().get(keys.get(4)));
    }

    @Test
    void actualXaddFailureDoesNotConsumeInventory() {
        redis.execute((org.springframework.data.redis.core.RedisCallback<Object>) connection ->
                connection.execute("XADD", bytes(keys.get(4)), bytes("18446744073709551615-18446744073709551615"),
                        bytes("marker"), bytes("test")));
        assertEquals(8L, code(admit("7", "100", "1", "1")));
        assertEquals("3", redis.opsForValue().get(keys.get(0)));
        assertFalse(redis.hasKey(keys.get(1)));
        assertFalse(redis.hasKey(keys.get(3)));
        assertEquals(1L, redis.opsForStream().size(keys.get(4)));
    }

    @Test
    void legacyParticipationIsNotResetAndExpiredInventoryIsNotRebuilt() {
        redis.opsForSet().add(keys.get(1), "7");
        assertEquals(2L, code(admit("7", "100", "1", "1")));
        assertEquals(1L, initialize(99));
        assertEquals("3", redis.opsForValue().get(keys.get(0)));
        redis.delete(keys.get(0));
        assertEquals(8L, code(admit("8", "101", "1", "1")));
        assertFalse(redis.hasKey(keys.get(0)));
    }

    @Test
    void expiredRulesReturnEndedEvenAfterAllActivityKeysHaveExpired() {
        redis.delete(keys.subList(0, 4));
        begin = System.currentTimeMillis() - 172800000;
        end = System.currentTimeMillis() - 86400000;
        assertEquals(5L, code(admit("7", "100", "1", "1")));
        assertFalse(redis.hasKey(keys.get(0)));
        assertFalse(redis.hasKey(keys.get(4)));
    }

    @Test
    void allActivityKeysHaveTheSameFixedDeadlineButStreamDoesNotExpire() {
        admit("7", "100", "1", "1");
        long expected = end + 86400000 - System.currentTimeMillis();
        for (int i = 0; i < 4; i++) {
            Long ttl = redis.getExpire(keys.get(i), TimeUnit.MILLISECONDS);
            assertNotNull(ttl);
            assertTrue(ttl > expected - 5000 && ttl <= expected + 1000);
        }
        assertEquals(-1L, redis.getExpire(keys.get(4), TimeUnit.MILLISECONDS));
    }

    @Test
    void concurrentDifferentUsersCannotOversellOrAppendExtraEvents() throws Exception {
        List<Callable<List<?>>> calls = new ArrayList<>();
        for (int user = 1; user <= 40; user++) {
            final String id = user + "";
            calls.add(() -> admit(id, "1000" + id, "1", "1"));
        }
        List<List<?>> results = concurrently(calls);
        assertEquals(3, results.stream().filter(result -> code(result) == 0).count());
        assertEquals(37, results.stream().filter(result -> code(result) == 1).count());
        assertEquals("0", redis.opsForValue().get(keys.get(0)));
        assertEquals(3L, redis.opsForSet().size(keys.get(1)));
        assertEquals(3L, redis.opsForHash().size(keys.get(3)));
        assertEquals(3L, redis.opsForStream().size(keys.get(4)));
    }

    @Test
    void concurrentSameUserGetsExactlyOneOriginalIdAndOneEvent() throws Exception {
        List<Callable<List<?>>> calls = new ArrayList<>();
        for (int index = 1; index <= 30; index++) {
            final String id = "9007199254741" + String.format("%03d", index);
            calls.add(() -> admit("7", id, "1", "1"));
        }
        List<List<?>> results = concurrently(calls);
        assertEquals(1, results.stream().filter(result -> code(result) == 0).count());
        assertEquals(29, results.stream().filter(result -> code(result) == 9).count());
        assertEquals(1, results.stream().map(result -> result.get(1)).distinct().count());
        assertEquals("2", redis.opsForValue().get(keys.get(0)));
        assertEquals(1L, redis.opsForStream().size(keys.get(4)));
    }

    private List<List<?>> concurrently(List<Callable<List<?>>> calls) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(8);
        try {
            List<List<?>> values = new ArrayList<>();
            for (Future<List<?>> future : pool.invokeAll(calls, 10, TimeUnit.SECONDS)) values.add(future.get());
            return values;
        } finally {
            pool.shutdownNow();
            assertTrue(pool.awaitTermination(5, TimeUnit.SECONDS));
        }
    }

    private Long initialize(int stock) {
        return redis.execute(INITIALIZE, keys.subList(0, 4), stock + "", begin + "", end + "", (end + 86400000) + "");
    }

    private List<?> admit(String userId, String orderId, String eligible, String status) {
        return redis.execute(ADMIT, keys, userId, orderId, eligible, status, begin + "", end + "", "9");
    }

    private void metadata(String name, String value) { redis.opsForHash().put(keys.get(2), name, value); }
    private byte[] bytes(String value) { return value.getBytes(java.nio.charset.StandardCharsets.UTF_8); }
    private long code(List<?> response) { return (Long) response.get(0); }
    private void assertUnmodified() {
        assertEquals("3", redis.opsForValue().get(keys.get(0)));
        assertFalse(redis.hasKey(keys.get(1)));
        assertFalse(redis.hasKey(keys.get(3)));
        assertFalse(redis.hasKey(keys.get(4)));
    }
}
