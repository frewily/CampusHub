package io.github.frewily.campushub.engagement.infrastructure;

import io.github.frewily.campushub.engagement.application.CheckInService;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.data.redis.core.RedisCallback;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;

import java.net.ServerSocket;
import java.nio.file.Path;
import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

/** Starts only its own non-persistent localhost Redis; no personal configuration or shared database. */
class CheckInRedisIT {
    @TempDir static Path directory;
    private static Process process;
    private static LettuceConnectionFactory factory;
    private static StringRedisTemplate redis;
    private long userId;

    @BeforeAll
    static void startRedis() throws Exception {
        try {
            int port;
            try (ServerSocket socket = new ServerSocket(0)) { port = socket.getLocalPort(); }
            process = new ProcessBuilder(System.getenv().getOrDefault("REDIS_SERVER_BINARY", "redis-server"),
                    "--bind", "127.0.0.1", "--port", Integer.toString(port), "--save", "", "--appendonly", "no",
                    "--dir", directory.toString()).redirectErrorStream(true)
                    .redirectOutput(directory.resolve("redis.log").toFile()).start();
            System.out.println("CHECKIN_REDIS_PORT=" + port);
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
            stopRedis();
            throw failure;
        }
    }

    @AfterAll
    static void stopRedis() throws Exception {
        try { if (factory != null) factory.destroy(); }
        finally {
            factory = null;
            redis = null;
            if (process != null) {
                Process owned = process;
                process = null;
                owned.destroy();
                if (!owned.waitFor(5, TimeUnit.SECONDS)) owned.destroyForcibly();
                assertTrue(owned.waitFor(5, TimeUnit.SECONDS) && !owned.isAlive(), "private Redis must stop");
            }
        }
    }

    @BeforeEach
    void identity() { userId = (UUID.randomUUID().getMostSignificantBits() & Long.MAX_VALUE) | 1L; }

    @Test
    void preexistingLegacyBitmapAndUnsigned31BitReadRemainCompatible() {
        String key = key("202610");
        for (int day = 0; day < 31; day++) redis.opsForValue().setBit(key, day, true);
        assertEquals(2147483647L, new RedisCheckInStore(redis).signedDays(userId, LocalDate.of(2026, 10, 31)));
        assertEquals(31, service("2026-10-31").signCount(userId));
        assertEquals(-1L, redis.getExpire(key, TimeUnit.SECONDS));
    }

    @Test
    void firstAndLastDaysUseOffsetsZeroAndThirty() {
        service("2026-10-01").sign(userId);
        service("2026-10-31").sign(userId);
        assertTrue(redis.opsForValue().getBit(key("202610"), 0));
        assertTrue(redis.opsForValue().getBit(key("202610"), 30));
        assertFalse(redis.opsForValue().getBit(key("202610"), 31));
        assertEquals(1, service("2026-10-31").signCount(userId));
    }

    @Test
    void futureBitsAreIgnoredAndUnsignedWidthEndsAtToday() {
        service("2026-10-17").sign(userId);
        service("2026-10-19").sign(userId);
        assertEquals(0, service("2026-10-18").signCount(userId));
        service("2026-10-18").sign(userId);
        assertEquals(2, service("2026-10-18").signCount(userId));
        assertEquals(3, service("2026-10-19").signCount(userId));
    }

    @Test
    void gapStopsConsecutiveCountWithoutChangingAnyBits() {
        for (String date : new String[]{"2026-10-14", "2026-10-16", "2026-10-17", "2026-10-18"}) service(date).sign(userId);
        String before = redis.opsForValue().get(key("202610"));
        assertEquals(3, service("2026-10-18").signCount(userId));
        assertEquals(before, redis.opsForValue().get(key("202610")));
    }

    @Test
    void repeatedSigningKeepsOneBitAndNoExpiry() {
        for (int i = 0; i < 20; i++) service("2026-10-18").sign(userId);
        Long count = redis.execute((RedisCallback<Long>) connection -> connection.bitCount(key("202610").getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        assertEquals(1L, count);
        assertEquals(1, service("2026-10-18").signCount(userId));
        assertEquals(-1L, redis.getExpire(key("202610"), TimeUnit.SECONDS));
    }

    @Test
    void monthYearAndLeapDayRemainSeparateWithoutCrossMonthStreaks() {
        for (String date : new String[]{"2026-12-31", "2027-01-01", "2028-02-29", "2028-03-01"}) {
            service(date).sign(userId);
            assertEquals(1, service(date).signCount(userId));
        }
        assertTrue(redis.opsForValue().getBit(key("202802"), 28));
        assertTrue(redis.opsForValue().getBit(key("202803"), 0));
        assertEquals(0, service("2027-02-01").signCount(userId));
    }

    @Test
    void separateIdentitiesAndRecreatedServicesUseOnlyRedisState() {
        service("2026-10-18").sign(userId);
        assertEquals(1, service("2026-10-18").signCount(userId));
        assertEquals(0, service("2026-10-18").signCount(userId - 1L));
    }

    private CheckInService service(String date) {
        Clock clock = Clock.fixed(LocalDate.parse(date).atStartOfDay().toInstant(ZoneOffset.UTC), ZoneOffset.UTC);
        return new CheckInService(new RedisCheckInStore(redis), clock);
    }

    private String key(String month) { return "sign:" + userId + ":" + month; }
}
