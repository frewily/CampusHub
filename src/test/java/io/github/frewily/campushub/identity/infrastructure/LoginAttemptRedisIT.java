package io.github.frewily.campushub.identity.infrastructure;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.RedisCallback;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.net.ServerSocket;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static io.github.frewily.campushub.utils.RedisConstants.*;
import static org.junit.jupiter.api.Assertions.*;

/** Own ephemeral localhost Redis only; test data uses synthetic phone keys. */
class LoginAttemptRedisIT {
    private static final String[] PHONES = {"13990007011", "13990007012", "13990007013", "13990007014"};
    @TempDir static Path directory;
    private static Process process;
    private static LettuceConnectionFactory factory;
    private static StringRedisTemplate redis;

    @BeforeAll static void startPrivateRedis() throws Exception {
        try {
            int port;
            try (ServerSocket socket = new ServerSocket(0)) { port = socket.getLocalPort(); }
            System.out.println("LOGIN_ATTEMPT_REDIS_PORT=" + port);
            process = new ProcessBuilder(System.getenv().getOrDefault("REDIS_SERVER_BINARY", "redis-server"),
                    "--bind", "127.0.0.1", "--port", Integer.toString(port), "--save", "", "--appendonly", "no",
                    "--dir", directory.toString()).redirectErrorStream(true)
                    .redirectOutput(directory.resolve("redis.log").toFile()).start();
            factory = new LettuceConnectionFactory("127.0.0.1", port);
            factory.afterPropertiesSet();
            redis = new StringRedisTemplate(factory);
            redis.afterPropertiesSet();
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
            while (process.isAlive() && System.nanoTime() < deadline) {
                try {
                    if ("PONG".equals(redis.execute((RedisCallback<String>) connection -> connection.ping()))) return;
                } catch (RuntimeException notReady) { Thread.sleep(25); }
            }
            throw new IllegalStateException("Private Redis did not become ready");
        } catch (Exception | Error failure) {
            stopPrivateRedis();
            throw failure;
        }
    }

    @AfterAll static void stopPrivateRedis() throws Exception {
        try { if (factory != null) factory.destroy(); }
        finally {
            factory = null; redis = null;
            if (process != null) {
                Process owned = process; process = null; owned.destroy();
                if (!owned.waitFor(5, TimeUnit.SECONDS)) owned.destroyForcibly();
                assertTrue(owned.waitFor(5, TimeUnit.SECONDS) && !owned.isAlive(), "owned Redis must stop");
            }
        }
    }

    @AfterEach void cleanSyntheticKeys() {
        for (String phone : PHONES) {
            redis.delete(codeKey(phone));
            redis.delete(cooldownKey(phone));
            redis.delete(failureKey(phone));
        }
    }

    @Test void matchingCodeIsConsumedOnlyOnceAndOtherKeysAndPhonesRemainIsolated() {
        String phone = PHONES[0], otherPhone = PHONES[1];
        RedisLoginAttemptStore store = new RedisLoginAttemptStore(redis);
        redis.opsForValue().set(codeKey(phone), "482915", 2, TimeUnit.MINUTES);
        redis.opsForValue().set(codeKey(otherPhone), "731604", 2, TimeUnit.MINUTES);
        redis.opsForValue().set(cooldownKey(phone), "cooldown", 60, TimeUnit.SECONDS);
        redis.opsForValue().set(failureKey(phone), "3", 600, TimeUnit.SECONDS);

        assertEquals(Long.valueOf(1), store.consumeCode(phone, "482915"));
        assertEquals(Long.valueOf(0), store.consumeCode(phone, "482915"));
        assertEquals("731604", redis.opsForValue().get(codeKey(otherPhone)));
        assertEquals("cooldown", redis.opsForValue().get(cooldownKey(phone)));
        assertEquals("3", redis.opsForValue().get(failureKey(phone)));
    }

    @Test void wrongCodeDoesNotConsumeOrRefreshCodeTtl() {
        String phone = PHONES[0];
        RedisLoginAttemptStore store = new RedisLoginAttemptStore(redis);
        redis.opsForValue().set(codeKey(phone), "482915", 2, TimeUnit.MINUTES);
        redis.expire(codeKey(phone), 40, TimeUnit.SECONDS);
        long before = redis.getExpire(codeKey(phone), TimeUnit.MILLISECONDS);

        assertEquals(Long.valueOf(0), store.consumeCode(phone, "000000"));
        long after = redis.getExpire(codeKey(phone), TimeUnit.MILLISECONDS);
        assertEquals("482915", redis.opsForValue().get(codeKey(phone)));
        assertTrue(after <= before && before - after < 3000, "wrong code must not refresh the existing TTL");
    }

    @Test void countGetsTenMinuteTtlOnlyOnFirstIncrementAndClearDeletesOnlyCount() {
        String phone = PHONES[0];
        RedisLoginAttemptStore store = new RedisLoginAttemptStore(redis);
        redis.opsForValue().set(codeKey(phone), "482915", 2, TimeUnit.MINUTES);
        redis.opsForValue().set(cooldownKey(phone), "cooldown", 60, TimeUnit.SECONDS);

        assertEquals(Long.valueOf(1), store.incrementFailureCount(phone));
        long firstTtl = redis.getExpire(failureKey(phone), TimeUnit.SECONDS);
        assertTrue(firstTtl >= 599 && firstTtl <= 600, "first increment establishes 600-second TTL");
        redis.expire(failureKey(phone), 20, TimeUnit.SECONDS);
        long agedTtl = redis.getExpire(failureKey(phone), TimeUnit.MILLISECONDS);
        assertEquals(Long.valueOf(2), store.incrementFailureCount(phone));
        long afterIncrement = redis.getExpire(failureKey(phone), TimeUnit.MILLISECONDS);
        assertTrue(afterIncrement <= agedTtl && agedTtl - afterIncrement < 3000,
                "later increments must not refresh the existing TTL");

        store.clearFailureCount(phone);
        assertNull(redis.opsForValue().get(failureKey(phone)));
        assertEquals("482915", redis.opsForValue().get(codeKey(phone)));
        assertEquals("cooldown", redis.opsForValue().get(cooldownKey(phone)));
    }

    @Test void redisWrongTypeFailuresPropagateAndOperationsRecoverAfterRemovingBadKeys() {
        String phone = PHONES[0];
        RedisLoginAttemptStore store = new RedisLoginAttemptStore(redis);
        redis.opsForHash().put(failureKey(phone), "field", "not-a-string");
        assertThrows(RuntimeException.class, () -> store.findFailureCount(phone));
        assertThrows(RuntimeException.class, () -> store.incrementFailureCount(phone));
        redis.delete(failureKey(phone));
        assertNull(store.findFailureCount(phone));
        assertEquals(Long.valueOf(1), store.incrementFailureCount(phone));

        redis.opsForHash().put(codeKey(phone), "field", "not-a-string");
        assertThrows(RuntimeException.class, () -> store.consumeCode(phone, "482915"));
        redis.delete(codeKey(phone));
        redis.opsForValue().set(codeKey(phone), "482915", 2, TimeUnit.MINUTES);
        assertEquals(Long.valueOf(1), store.consumeCode(phone, "482915"));
    }

    @Test void concurrentMatchingConsumersHaveExactlyOneWinner() throws Exception {
        String phone = PHONES[0];
        RedisLoginAttemptStore store = new RedisLoginAttemptStore(redis);
        redis.opsForValue().set(codeKey(phone), "482915", 2, TimeUnit.MINUTES);
        int workers = 4;
        ExecutorService executor = Executors.newFixedThreadPool(workers);
        CountDownLatch ready = new CountDownLatch(workers);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<Long>> results = new ArrayList<Future<Long>>();
        try {
            for (int i = 0; i < workers; i++) {
                results.add(executor.submit(() -> {
                    ready.countDown();
                    if (!start.await(5, TimeUnit.SECONDS)) throw new IllegalStateException("start gate timed out");
                    return store.consumeCode(phone, "482915");
                }));
            }
            assertTrue(ready.await(5, TimeUnit.SECONDS), "workers must be ready before release");
            start.countDown();
            int winners = 0;
            for (Future<Long> result : results) {
                Long value = result.get(5, TimeUnit.SECONDS);
                if (Long.valueOf(1).equals(value)) winners++;
                else assertEquals(Long.valueOf(0), value);
            }
            assertEquals(1, winners);
        } finally {
            start.countDown();
            executor.shutdownNow();
            assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS), "consumer workers must stop");
        }
    }

    private String codeKey(String phone) { return LOGIN_CODE_KEY + phone; }
    private String cooldownKey(String phone) { return LOGIN_CODE_COOLDOWN_KEY + phone; }
    private String failureKey(String phone) { return LOGIN_FAILURE_KEY + phone; }
}
