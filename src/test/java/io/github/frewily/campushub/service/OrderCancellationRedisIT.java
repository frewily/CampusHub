package io.github.frewily.campushub.service;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.RedisCallback;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.data.redis.connection.RedisPassword;
import org.springframework.data.redis.connection.RedisStandaloneConfiguration;

import java.net.ServerSocket;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

/** Explicit -Dtest=OrderCancellationRedisIT; owns an isolated, non-persistent Redis process. */
class OrderCancellationRedisIT {
    private static final DefaultRedisScript<Long> RELEASE = new DefaultRedisScript<>();

    @TempDir
    static Path directory;

    private static Process process;
    private static LettuceConnectionFactory factory;
    private static StringRedisTemplate redis;
    private static int redisPort;
    private Fixture fixture;

    @BeforeAll
    static void startRedis() throws Exception {
        try (ServerSocket socket = new ServerSocket(0)) {
            redisPort = socket.getLocalPort();
        }
        process = new ProcessBuilder(System.getenv().getOrDefault("REDIS_SERVER_BINARY", "redis-server"),
                "--bind", "127.0.0.1", "--port", Integer.toString(redisPort), "--save", "", "--appendonly", "no",
                "--dir", directory.toString()).redirectErrorStream(true)
                .redirectOutput(directory.resolve("redis.log").toFile()).start();
        factory = new LettuceConnectionFactory("127.0.0.1", redisPort);
        factory.afterPropertiesSet();
        redis = new StringRedisTemplate(factory);
        redis.afterPropertiesSet();

        long startupDeadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        boolean ready = false;
        while (System.nanoTime() < startupDeadline && process.isAlive()) {
            try {
                ready = "PONG".equals(redis.execute((RedisCallback<String>) connection -> connection.ping()));
                if (ready) {
                    break;
                }
            } catch (RuntimeException unavailable) {
                Thread.sleep(20);
            }
        }
        assertTrue(ready, "isolated Redis failed to start");
        RELEASE.setLocation(new ClassPathResource("release-cancelled-order.lua"));
        RELEASE.setResultType(Long.class);
    }

    @AfterAll
    static void stopRedis() throws Exception {
        if (factory != null) {
            factory.destroy();
        }
        if (process != null) {
            process.destroy();
            if (!process.waitFor(5, TimeUnit.SECONDS)) {
                process.destroyForcibly();
                assertTrue(process.waitFor(5, TimeUnit.SECONDS), "test-owned Redis did not exit");
            }
        }
    }

    @BeforeEach
    void createIsolatedFixture() {
        fixture = new Fixture();
        seed(fixture);
    }

    @Test
    void releases64BitOrderOnceAndPreservesOriginalRequestAndParticipation() {
        fixture.orderId = "9007199254740993";
        redis.opsForHash().put(fixture.request, fixture.userId, fixture.orderId);
        redis.opsForSet().add(fixture.participants, fixture.userId);

        assertEquals(0L, release(fixture, fixture.userId, fixture.orderId, fixture.deadline));
        assertEquals(6L, stock(fixture));
        assertEquals("DONE:" + fixture.userId, redis.opsForHash().get(fixture.released, fixture.orderId));

        assertEquals(1L, release(fixture, fixture.userId, fixture.orderId, fixture.deadline));
        assertEquals(6L, stock(fixture), "duplicate cancellation must not return inventory twice");
        assertEquals(fixture.orderId, redis.opsForHash().get(fixture.request, fixture.userId));
        assertTrue(redis.opsForSet().isMember(fixture.participants, fixture.userId));
    }

    @Test
    void concurrentRetriesReturnInventoryExactlyOnce() throws Exception {
        ExecutorService workers = Executors.newFixedThreadPool(20);
        try {
            List<Callable<Long>> calls = new ArrayList<>();
            for (int i = 0; i < 20; i++) {
                calls.add(() -> release(fixture, fixture.userId, fixture.orderId, fixture.deadline));
            }
            List<Future<Long>> futures = workers.invokeAll(calls, 10, TimeUnit.SECONDS);
            int released = 0;
            int alreadyReleased = 0;
            for (Future<Long> future : futures) {
                long result = future.get();
                if (result == 0L) {
                    released++;
                } else if (result == 1L) {
                    alreadyReleased++;
                } else {
                    fail("unexpected cancellation result: " + result);
                }
            }
            assertEquals(1, released);
            assertEquals(19, alreadyReleased);
            assertEquals(6L, stock(fixture));
        } finally {
            workers.shutdownNow();
            assertTrue(workers.awaitTermination(5, TimeUnit.SECONDS));
        }
    }

    @Test
    void rejectsDifferentUserAndDifferentOriginalOrderWithoutMutation() {
        assertEquals(4L, release(fixture, "user-999", fixture.orderId, fixture.deadline));
        assertEquals(4L, release(fixture, fixture.userId, "order-999", fixture.deadline));
        assertEquals(5L, stock(fixture));
        assertNull(redis.opsForHash().get(fixture.released, fixture.orderId));
        assertEquals(fixture.orderId, redis.opsForHash().get(fixture.request, fixture.userId));
    }

    @Test
    void wrongRedisKeyTypesFailClosedWithoutChangingFixture() {
        Fixture wrongStock = copyFixture();
        redis.delete(wrongStock.stock);
        redis.opsForHash().put(wrongStock.stock, "bad", "5");
        assertInvalidAndUnchanged(wrongStock);
        assertEquals("5", redis.opsForHash().get(wrongStock.stock, "bad"));
        assertReservationStillPresent(wrongStock);
        assertNull(redis.opsForHash().get(wrongStock.released, wrongStock.orderId));

        Fixture wrongActivity = copyFixture();
        redis.delete(wrongActivity.activity);
        redis.opsForValue().set(wrongActivity.activity, "bad-type");
        assertInvalidAndUnchanged(wrongActivity);
        assertEquals("bad-type", redis.opsForValue().get(wrongActivity.activity));
        assertEquals(5L, stock(wrongActivity));
        assertReservationStillPresent(wrongActivity);
        assertNull(redis.opsForHash().get(wrongActivity.released, wrongActivity.orderId));

        Fixture wrongRequest = copyFixture();
        redis.delete(wrongRequest.request);
        redis.opsForValue().set(wrongRequest.request, "bad-type");
        assertInvalidAndUnchanged(wrongRequest);
        assertEquals("bad-type", redis.opsForValue().get(wrongRequest.request));
        assertEquals(5L, stock(wrongRequest));
        assertNull(redis.opsForHash().get(wrongRequest.released, wrongRequest.orderId));

        Fixture wrongReleased = copyFixture();
        redis.opsForValue().set(wrongReleased.released, "bad-type");
        assertInvalidAndUnchanged(wrongReleased);
        assertEquals("bad-type", redis.opsForValue().get(wrongReleased.released));
        assertEquals(5L, stock(wrongReleased));
        assertReservationStillPresent(wrongReleased);
    }

    @Test
    void malformedStockAndMismatchedOriginalDeadlineFailWithoutMutation() {
        for (String invalidStock : Arrays.asList("bad", "-1", "1.5", "2147483647")) {
            Fixture badStock = copyFixture();
            redis.opsForValue().set(badStock.stock, invalidStock);
            assertInvalidAndUnchanged(badStock);
            assertEquals(invalidStock, redis.opsForValue().get(badStock.stock));
            assertReservationStillPresent(badStock);
            assertNull(redis.opsForHash().get(badStock.released, badStock.orderId));
        }

        Fixture wrongDeadline = copyFixture();
        redis.opsForHash().put(wrongDeadline.activity, "expireAt", Long.toString(wrongDeadline.deadline + 1));
        assertInvalidAndUnchanged(wrongDeadline);
        assertEquals("5", redis.opsForValue().get(wrongDeadline.stock));
        assertEquals(Long.toString(wrongDeadline.deadline + 1),
                redis.opsForHash().get(wrongDeadline.activity, "expireAt"));
        assertReservationStillPresent(wrongDeadline);
        assertNull(redis.opsForHash().get(wrongDeadline.released, wrongDeadline.orderId));
    }

    @Test
    void expiredActivityIsNotRecreatedAndReturnsExpired() {
        Fixture expired = new Fixture();
        expired.deadline = System.currentTimeMillis() - 1000;

        assertEquals(2L, release(expired, expired.userId, expired.orderId, expired.deadline));
        assertFalse(redis.hasKey(expired.stock));
        assertFalse(redis.hasKey(expired.activity));
        assertFalse(redis.hasKey(expired.request));
        assertFalse(redis.hasKey(expired.released));
        assertFalse(redis.hasKey(expired.participants));
    }

    @Test
    void releaseMarkerUsesTheOriginalFixedActivityDeadline() {
        long startedAt = System.currentTimeMillis();
        assertEquals(0L, release(fixture, fixture.userId, fixture.orderId, fixture.deadline));

        Long markerTtl = redis.getExpire(fixture.released, TimeUnit.MILLISECONDS);
        long expectedTtl = fixture.deadline - System.currentTimeMillis();
        assertNotNull(markerTtl);
        assertTrue(markerTtl > 0L);
        assertTrue(markerTtl <= fixture.deadline - startedAt);
        assertTrue(Math.abs(markerTtl - expectedTtl) <= 1000L,
                "marker TTL should end at the original activity deadline");
    }

    @Test
    void aclFailureAfterMarkerWriteRequiresReviewOnRetry() {
        String aclUser = "release-test-" + UUID.randomUUID().toString().replace("-", "");
        String aclReply = redis.execute((RedisCallback<String>) connection -> new String((byte[]) connection.execute(
                "ACL", bytes("SETUSER"), bytes(aclUser), bytes("reset"), bytes("on"), bytes("nopass"),
                bytes("+@all"), bytes("-incrby"), bytes("~*")), java.nio.charset.StandardCharsets.UTF_8));
        assertEquals("OK", aclReply);

        RedisStandaloneConfiguration restrictedConfiguration =
                new RedisStandaloneConfiguration("127.0.0.1", redisPort);
        restrictedConfiguration.setUsername(aclUser);
        // A nopass ACL user accepts any password; this placeholder makes Lettuce send AUTH user password.
        restrictedConfiguration.setPassword(RedisPassword.of("test-only-no-secret"));
        LettuceConnectionFactory restrictedFactory = new LettuceConnectionFactory(restrictedConfiguration);
        try {
            restrictedFactory.afterPropertiesSet();
            StringRedisTemplate restrictedRedis = new StringRedisTemplate(restrictedFactory);
            restrictedRedis.afterPropertiesSet();
            String authenticatedUser = restrictedRedis.execute((RedisCallback<String>) connection ->
                    new String((byte[]) connection.execute("ACL", bytes("WHOAMI")),
                            java.nio.charset.StandardCharsets.UTF_8));
            assertEquals(aclUser, authenticatedUser, "the script client must authenticate as the restricted ACL user");
            String incrementDryRun = redis.execute((RedisCallback<String>) connection -> new String(
                    (byte[]) connection.execute("ACL", bytes("DRYRUN"), bytes(aclUser), bytes("INCRBY"),
                            bytes(fixture.stock), bytes("1")), java.nio.charset.StandardCharsets.UTF_8));
            assertTrue(incrementDryRun.toLowerCase().contains("permission"),
                    "ACL DRYRUN must confirm INCRBY is denied, got: " + incrementDryRun);

            assertThrows(RuntimeException.class,
                    () -> executeRelease(restrictedRedis, fixture, fixture.userId, fixture.orderId, fixture.deadline));
            assertEquals(5L, stock(fixture), "ACL denial must not increment stock");
            assertEquals("PENDING:" + fixture.userId,
                    redis.opsForHash().get(fixture.released, fixture.orderId),
                    "the incomplete release must retain a pending marker for review on retry");

            long retryResult = executeRelease(redis, fixture, fixture.userId, fixture.orderId, fixture.deadline);
            assertEquals(3L, retryResult, "an incomplete release marker must require review on retry");
            assertEquals(5L, stock(fixture), "retry must not increment stock after the ACL failure");
        } finally {
            restrictedFactory.destroy();
        }
    }

    @Test
    void pendingUnknownAndLegacyMarkersRequireReviewWithoutChangingReservation() {
        // Model a crash after INCRBY succeeded but before the script could write DONE.
        redis.opsForValue().set(fixture.stock, "6");
        redis.opsForHash().put(fixture.released, fixture.orderId, "PENDING:" + fixture.userId);

        assertMarkerRequiresReviewAndPreservesReservation("PENDING:" + fixture.userId);

        redis.opsForHash().put(fixture.released, fixture.orderId, "UNRECOGNIZED");
        assertMarkerRequiresReviewAndPreservesReservation("UNRECOGNIZED");

        redis.opsForHash().put(fixture.released, fixture.orderId, fixture.userId);
        assertMarkerRequiresReviewAndPreservesReservation(fixture.userId);
    }

    private Fixture copyFixture() {
        Fixture copy = new Fixture();
        seed(copy);
        return copy;
    }

    private void seed(Fixture current) {
        redis.opsForValue().set(current.stock, "5");
        redis.opsForHash().put(current.activity, "expireAt", Long.toString(current.deadline));
        redis.opsForHash().put(current.request, current.userId, current.orderId);
        redis.opsForSet().add(current.participants, current.userId);
    }

    private void assertInvalidAndUnchanged(Fixture current) {
        assertEquals(3L, release(current, current.userId, current.orderId, current.deadline));
    }

    private void assertReservationStillPresent(Fixture current) {
        assertEquals(current.orderId, redis.opsForHash().get(current.request, current.userId));
        assertTrue(redis.opsForSet().isMember(current.participants, current.userId));
    }

    private void assertMarkerRequiresReviewAndPreservesReservation(String marker) {
        assertEquals(3L, release(fixture, fixture.userId, fixture.orderId, fixture.deadline));
        assertEquals(6L, stock(fixture));
        assertEquals(marker, redis.opsForHash().get(fixture.released, fixture.orderId));
        assertReservationStillPresent(fixture);
    }

    private long release(Fixture current, String userId, String orderId, long deadline) {
        return executeRelease(redis, current, userId, orderId, deadline);
    }

    private long executeRelease(StringRedisTemplate client, Fixture current, String userId, String orderId,
            long deadline) {
        Long result = client.execute(RELEASE,
                Arrays.asList(current.stock, current.activity, current.request, current.released),
                userId, orderId, Long.toString(deadline));
        assertNotNull(result);
        return result;
    }

    private static byte[] bytes(String value) {
        return value.getBytes(java.nio.charset.StandardCharsets.UTF_8);
    }

    private long stock(Fixture current) {
        return Long.parseLong(redis.opsForValue().get(current.stock));
    }

    private static final class Fixture {
        private final String suffix = UUID.randomUUID().toString();
        private final String stock = "seckill:stock:" + suffix;
        private final String activity = "seckill:activity:" + suffix;
        private final String request = "seckill:request:" + suffix;
        private final String released = "seckill:released:" + suffix;
        private final String participants = "seckill:order:" + suffix;
        private final String userId = "700000000000000001";
        private String orderId = "9007199254740993";
        private long deadline = System.currentTimeMillis() + 60000L;
    }
}
