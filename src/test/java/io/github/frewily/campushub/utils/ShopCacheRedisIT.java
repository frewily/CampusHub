package io.github.frewily.campushub.utils;

import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import io.github.frewily.campushub.entity.Shop;
import io.github.frewily.campushub.exception.BusinessException;
import io.github.frewily.campushub.exception.ErrorCode;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.connection.RedisPassword;
import org.springframework.data.redis.connection.RedisStandaloneConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.RedisCallback;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;

import java.net.ServerSocket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.*;

/** Explicit -Dtest=ShopCacheRedisIT; owns an isolated, non-persistent Redis process. */
class ShopCacheRedisIT {
    private static final DefaultRedisScript<Long> PUBLISH = script("shop-cache-publish.lua", Long.class);
    private static final DefaultRedisScript<Long> UNLOCK = script("shop-cache-unlock.lua", Long.class);

    @TempDir
    static Path directory;

    private static Process process;
    private static LettuceConnectionFactory factory;
    private static StringRedisTemplate redis;
    private static int redisPort;

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

        long startupDeadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        boolean ready = false;
        while (System.nanoTime() < startupDeadline && process.isAlive()) {
            try {
                ready = "PONG".equals(redis.execute((RedisCallback<String>) connection -> connection.ping()));
                if (ready) break;
            } catch (RuntimeException unavailable) {
                Thread.sleep(20);
            }
        }
        assertTrue(ready, "test-owned Redis failed to start");
    }

    @AfterAll
    static void stopRedis() throws Exception {
        if (factory != null) factory.destroy();
        if (process != null) {
            process.destroy();
            if (!process.waitFor(3, TimeUnit.SECONDS)) {
                process.destroyForcibly();
                assertTrue(process.waitFor(3, TimeUnit.SECONDS), "test-owned Redis did not exit");
            }
        }
    }

    @Test
    void positiveCacheHitDoesNotInvokeLoaderTwice() {
        CacheClient client = new CacheClient(redis);
        Long shopId = nextId();
        AtomicInteger loads = new AtomicInteger();
        Function<Long, Shop> loader = id -> {
            loads.incrementAndGet();
            return shop(id, "Campus cafe");
        };

        assertEquals("Campus cafe", client.queryShop(shopId, Shop.class, loader).getName());
        assertEquals("Campus cafe", client.queryShop(shopId, Shop.class, loader).getName());
        assertEquals(1, loads.get());
    }

    @Test
    void negativeCacheHasShortTtlAndServesNegativeHit() {
        CacheClient client = new CacheClient(redis);
        Long shopId = nextId();
        AtomicInteger loads = new AtomicInteger();

        assertNull(client.queryShop(shopId, Shop.class, ignored -> {
            loads.incrementAndGet();
            return null;
        }));
        assertNull(client.queryShop(shopId, Shop.class, ignored -> {
            loads.incrementAndGet();
            return null;
        }));

        assertEquals(1, loads.get());
        Long ttl = redis.getExpire(CacheClient.DATA_PREFIX + shopId, TimeUnit.MILLISECONDS);
        assertNotNull(ttl);
        assertTrue(ttl >= 9_000L && ttl <= 15_000L, "negative cache TTL should be within its short range");
        assertEquals(1L, client.statistics()[1], "second query should be counted as a negative hit");
    }

    @Test
    void physicallyExpiredOwnedCacheKeyIsLoadedAgain() throws Exception {
        CacheClient client = new CacheClient(redis);
        Long shopId = nextId();
        AtomicInteger loads = new AtomicInteger();

        Shop first = client.queryShop(shopId, Shop.class, id -> {
            loads.incrementAndGet();
            return shop(id, "before physical expiry");
        });
        assertEquals("before physical expiry", first.getName());
        assertTrue(redis.expire(CacheClient.DATA_PREFIX + shopId, 1, TimeUnit.MILLISECONDS));

        long expiryDeadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(1_000);
        while (System.nanoTime() < expiryDeadline && Boolean.TRUE.equals(redis.hasKey(CacheClient.DATA_PREFIX + shopId))) {
            Thread.sleep(10);
        }
        assertFalse(Boolean.TRUE.equals(redis.hasKey(CacheClient.DATA_PREFIX + shopId)),
                "test-owned data key should physically expire within the bounded poll");

        Shop afterExpiry = client.queryShop(shopId, Shop.class, id -> {
            loads.incrementAndGet();
            return shop(id, "after physical expiry");
        });
        assertEquals("after physical expiry", afterExpiry.getName());
        assertEquals(2, loads.get());
    }

    @Test
    void ignoresLegacyCacheShopPayload() {
        CacheClient client = new CacheClient(redis);
        Long shopId = nextId();
        redis.opsForValue().set("cache:shop:" + shopId, JSONUtil.toJsonStr(shop(shopId, "legacy stale")));
        AtomicInteger loads = new AtomicInteger();

        Shop result = client.queryShop(shopId, Shop.class, id -> {
            loads.incrementAndGet();
            return shop(id, "fresh");
        });

        assertEquals("fresh", result.getName());
        assertEquals(1, loads.get());
        assertEquals("legacy stale", JSONUtil.toBean(redis.opsForValue().get("cache:shop:" + shopId), Shop.class)
                .getName());
    }

    @Test
    void twentyConcurrentColdMissesUseOneLoaderAndThenHitCache() throws Exception {
        CacheClient client = new CacheClient(redis);
        Long shopId = nextId();
        AtomicInteger loads = new AtomicInteger();
        CountDownLatch ready = new CountDownLatch(20);
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService workers = Executors.newFixedThreadPool(20);
        try {
            List<Future<Shop>> futures = new ArrayList<>();
            for (int i = 0; i < 20; i++) {
                Callable<Shop> call = () -> {
                    ready.countDown();
                    if (!start.await(2, TimeUnit.SECONDS)) throw new IllegalStateException("start gate timed out");
                    return client.queryShop(shopId, Shop.class, id -> {
                        loads.incrementAndGet();
                        try {
                            Thread.sleep(50);
                        } catch (InterruptedException interrupted) {
                            Thread.currentThread().interrupt();
                            throw new IllegalStateException("loader interrupted", interrupted);
                        }
                        return shop(id, "single load");
                    });
                };
                futures.add(workers.submit(call));
            }
            assertTrue(ready.await(2, TimeUnit.SECONDS), "workers did not reach the start gate");
            start.countDown();

            int successfulQueries = 0;
            for (Future<Shop> future : futures) {
                try {
                    Shop result = future.get(2, TimeUnit.SECONDS);
                    assertEquals("single load", result.getName());
                    successfulQueries++;
                } catch (ExecutionException failure) {
                    assertInstanceOf(BusinessException.class, failure.getCause());
                    assertEquals(ErrorCode.SHOP_STATE_UNAVAILABLE,
                            ((BusinessException) failure.getCause()).getErrorCode());
                }
            }
            assertTrue(successfulQueries > 0, "at least one request should receive the cached value");
            assertEquals(1, loads.get(), "lock contention must not fan out to multiple DB loaders");
        } finally {
            start.countDown();
            workers.shutdownNow();
            assertTrue(workers.awaitTermination(3, TimeUnit.SECONDS));
        }

        assertEquals("single load", client.queryShop(shopId, Shop.class, id -> {
            loads.incrementAndGet();
            return shop(id, "unexpected extra load");
        }).getName());
        assertEquals(1, loads.get());
    }

    @Test
    void positiveAndNegativeTtlJitterStayWithinConfiguredIntervals() {
        Set<Long> positiveSamples = new HashSet<>();
        Set<Long> negativeSamples = new HashSet<>();
        for (int i = 0; i < 100; i++) {
            long positive = CacheClient.positiveTtlMillis();
            long negative = CacheClient.negativeTtlMillis();
            assertTrue(positive >= 60_000L && positive <= 75_000L);
            assertTrue(negative >= 10_000L && negative <= 15_000L);
            positiveSamples.add(positive);
            negativeSamples.add(negative);
        }
        assertTrue(positiveSamples.size() > 1, "positive TTL should include jitter");
        assertTrue(negativeSamples.size() > 1, "negative TTL should include jitter");

        CacheClient client = new CacheClient(redis);
        Long shopId = nextId();
        client.queryShop(shopId, Shop.class, id -> shop(id, "ttl"));
        Long storedTtl = redis.getExpire(CacheClient.DATA_PREFIX + shopId, TimeUnit.MILLISECONDS);
        assertNotNull(storedTtl);
        assertTrue(storedTtl >= 59_000L && storedTtl <= 75_000L);
    }

    @Test
    void invalidationDuringPausedOldReadCannotLeaveStalePayload() throws Exception {
        CacheClient client = new CacheClient(redis);
        Long shopId = nextId();
        CountDownLatch loaderEntered = new CountDownLatch(1);
        CountDownLatch releaseLoader = new CountDownLatch(1);
        AtomicInteger loads = new AtomicInteger();
        AtomicReference<Shop> current = new AtomicReference<>(shop(shopId, "old"));
        ExecutorService worker = Executors.newSingleThreadExecutor();
        try {
            Future<Shop> oldQuery = worker.submit(() -> client.queryShop(shopId, Shop.class, id -> {
                if (loads.incrementAndGet() == 1) {
                    loaderEntered.countDown();
                    try {
                        if (!releaseLoader.await(2, TimeUnit.SECONDS)) {
                            throw new IllegalStateException("paused loader timed out");
                        }
                    } catch (InterruptedException interrupted) {
                        Thread.currentThread().interrupt();
                        throw new IllegalStateException("paused loader interrupted", interrupted);
                    }
                    return shop(id, "old");
                }
                return current.get();
            }));

            assertTrue(loaderEntered.await(2, TimeUnit.SECONDS), "old loader did not reach pause");
            client.invalidateShop(shopId);
            current.set(shop(shopId, "fresh"));
            releaseLoader.countDown();

            try {
                Shop firstResult = oldQuery.get(2, TimeUnit.SECONDS);
                assertEquals("fresh", firstResult.getName(), "in-flight query may only return the new value");
            } catch (ExecutionException failure) {
                assertInstanceOf(BusinessException.class, failure.getCause());
                assertEquals(ErrorCode.SHOP_STATE_UNAVAILABLE,
                        ((BusinessException) failure.getCause()).getErrorCode());
            }
        } finally {
            releaseLoader.countDown();
            worker.shutdownNow();
            assertTrue(worker.awaitTermination(3, TimeUnit.SECONDS));
        }

        Shop subsequent = client.queryShop(shopId, Shop.class, id -> current.get());
        assertEquals("fresh", subsequent.getName());
        assertTrue(loads.get() >= 2);
    }

    @Test
    void lockContentionReturnsUnavailableWithoutCallingLoader() {
        CacheClient client = new CacheClient(redis);
        Long shopId = nextId();
        redis.opsForValue().set(CacheClient.LOCK_PREFIX + shopId, "other-worker", 10, TimeUnit.SECONDS);
        AtomicInteger loads = new AtomicInteger();

        BusinessException failure = assertThrows(BusinessException.class,
                () -> client.queryShop(shopId, Shop.class, id -> {
                    loads.incrementAndGet();
                    return shop(id, "must not load");
                }));

        assertEquals(ErrorCode.SHOP_STATE_UNAVAILABLE, failure.getErrorCode());
        assertEquals(0, loads.get());
    }

    @Test
    void staleLeaseTokenCannotPublishOrUnlockSuccessorLease() {
        Long shopId = nextId();
        String dataKey = CacheClient.DATA_PREFIX + shopId;
        String epochKey = CacheClient.EPOCH_PREFIX + shopId;
        String lockKey = CacheClient.LOCK_PREFIX + shopId;
        String oldToken = UUID.randomUUID().toString();
        String successorToken = UUID.randomUUID().toString();
        String epoch = UUID.randomUUID().toString();
        redis.opsForValue().set(epochKey, epoch);
        redis.opsForValue().set(lockKey, successorToken, 10, TimeUnit.SECONDS);

        Long publishResult = redis.execute(PUBLISH, Arrays.asList(dataKey, epochKey, lockKey), oldToken, epoch,
                "{}", "60000");
        Long unlockResult = redis.execute(UNLOCK, Arrays.asList(lockKey), oldToken);

        assertEquals(0L, publishResult);
        assertEquals(0L, unlockResult);
        assertNull(redis.opsForValue().get(dataKey));
        assertEquals(successorToken, redis.opsForValue().get(lockKey));
    }

    @Test
    void changedEpochFencesPublishEvenWhenLeaseTokenStillMatches() {
        Long shopId = nextId();
        String dataKey = CacheClient.DATA_PREFIX + shopId;
        String epochKey = CacheClient.EPOCH_PREFIX + shopId;
        String lockKey = CacheClient.LOCK_PREFIX + shopId;
        String token = UUID.randomUUID().toString();
        redis.opsForValue().set(epochKey, "new-epoch");
        redis.opsForValue().set(lockKey, token, 10, TimeUnit.SECONDS);

        Long result = redis.execute(PUBLISH, Arrays.asList(dataKey, epochKey, lockKey), token, "old-epoch",
                "{}", "60000");

        assertEquals(0L, result);
        assertNull(redis.opsForValue().get(dataKey));
        assertEquals(token, redis.opsForValue().get(lockKey));
    }

    @Test
    void repeatedInvalidationAlwaysChangesRedisEpoch() {
        CacheClient client = new CacheClient(redis);
        Long shopId = nextId();
        String epochKey = CacheClient.EPOCH_PREFIX + shopId;

        client.invalidateShop(shopId);
        String firstEpoch = redis.opsForValue().get(epochKey);
        client.invalidateShop(shopId);
        String secondEpoch = redis.opsForValue().get(epochKey);

        assertNotNull(firstEpoch);
        assertNotNull(secondEpoch);
        assertNotEquals(firstEpoch, secondEpoch, "every invalidate call must use a new random epoch");
        assertDoesNotThrow(() -> UUID.fromString(firstEpoch));
        assertDoesNotThrow(() -> UUID.fromString(secondEpoch));
    }

    @Test
    void wrongTypeAndMalformedJsonFailClosedWithoutCallingLoader() {
        CacheClient client = new CacheClient(redis);
        Long wrongTypeId = nextId();
        redis.opsForHash().put(CacheClient.DATA_PREFIX + wrongTypeId, "field", "value");
        assertUnavailableWithoutLoad(client, wrongTypeId);

        Long malformedId = nextId();
        redis.opsForValue().set(CacheClient.EPOCH_PREFIX + malformedId, "epoch");
        redis.opsForValue().set(CacheClient.DATA_PREFIX + malformedId, "{not-json");
        assertUnavailableWithoutLoad(client, malformedId);
    }

    @Test
    void aclFailureAfterEpochWriteLeavesOldPayloadButDefaultClientRebuildsFresh() {
        Long shopId = nextId();
        String dataKey = CacheClient.DATA_PREFIX + shopId;
        String epochKey = CacheClient.EPOCH_PREFIX + shopId;
        String legacyKey = "cache:shop:" + shopId;
        String oldEpoch = UUID.randomUUID().toString();
        JSONObject oldEnvelope = new JSONObject();
        oldEnvelope.set("epoch", oldEpoch).set("empty", false).set("data", shop(shopId, "old payload"));
        redis.opsForValue().set(epochKey, oldEpoch);
        redis.opsForValue().set(dataKey, oldEnvelope.toString());
        redis.opsForValue().set(legacyKey, "legacy payload");

        String aclUser = "shop-cache-test-" + UUID.randomUUID().toString().replace("-", "");
        String aclReply = redis.execute((RedisCallback<String>) connection -> new String((byte[]) connection.execute(
                "ACL", bytes("SETUSER"), bytes(aclUser), bytes("reset"), bytes("on"), bytes("nopass"),
                bytes("+@all"), bytes("-del"), bytes("~*")), StandardCharsets.UTF_8));
        assertEquals("OK", aclReply);

        RedisStandaloneConfiguration restrictedConfiguration =
                new RedisStandaloneConfiguration("127.0.0.1", redisPort);
        restrictedConfiguration.setUsername(aclUser);
        // nopass accepts any password; a public placeholder forces the client to send AUTH user password.
        restrictedConfiguration.setPassword(RedisPassword.of("test-only-no-secret"));
        LettuceConnectionFactory restrictedFactory = new LettuceConnectionFactory(restrictedConfiguration);
        try {
            restrictedFactory.afterPropertiesSet();
            StringRedisTemplate restrictedRedis = new StringRedisTemplate(restrictedFactory);
            restrictedRedis.afterPropertiesSet();
            String authenticatedUser = restrictedRedis.execute((RedisCallback<String>) connection ->
                    new String((byte[]) connection.execute("ACL", bytes("WHOAMI")), StandardCharsets.UTF_8));
            assertEquals(aclUser, authenticatedUser, "restricted client must authenticate as the ACL user");

            assertThrows(RuntimeException.class, () -> new CacheClient(restrictedRedis).invalidateShop(shopId));
            String changedEpoch = redis.opsForValue().get(epochKey);
            assertNotEquals(oldEpoch, changedEpoch, "epoch SET must precede the denied DEL");
            assertEquals(oldEnvelope.toString(), redis.opsForValue().get(dataKey),
                    "ACL denial must leave the old payload physically present");

            AtomicInteger loads = new AtomicInteger();
            CacheClient defaultClient = new CacheClient(redis);
            Shop rebuilt = defaultClient.queryShop(shopId, Shop.class, id -> {
                loads.incrementAndGet();
                return shop(id, "fresh after ACL failure");
            });
            assertEquals("fresh after ACL failure", rebuilt.getName());
            assertEquals(1, loads.get());
            assertEquals("legacy payload", redis.opsForValue().get(legacyKey));

            Shop hit = defaultClient.queryShop(shopId, Shop.class, id -> {
                loads.incrementAndGet();
                return shop(id, "unexpected reload");
            });
            assertEquals("fresh after ACL failure", hit.getName());
            assertEquals(1, loads.get());
        } finally {
            restrictedFactory.destroy();
            // Generic execute decodes bulk replies, but ACL DELUSER returns an integer.
            // Disable this private test user via SETUSER's OK reply; owned process shutdown removes it.
            redis.execute((RedisCallback<Object>) connection -> connection.execute(
                    "ACL", bytes("SETUSER"), bytes(aclUser), bytes("off")));
        }
    }

    private void assertUnavailableWithoutLoad(CacheClient client, Long shopId) {
        AtomicInteger loads = new AtomicInteger();
        BusinessException failure = assertThrows(BusinessException.class,
                () -> client.queryShop(shopId, Shop.class, id -> {
                    loads.incrementAndGet();
                    return shop(id, "must not load");
                }));
        assertEquals(ErrorCode.SHOP_STATE_UNAVAILABLE, failure.getErrorCode());
        assertEquals(0, loads.get());
    }

    private static Long nextId() {
        long candidate = UUID.randomUUID().getMostSignificantBits() & Long.MAX_VALUE;
        return candidate == 0L ? 1L : candidate;
    }

    private static Shop shop(Long id, String name) {
        return new Shop().setId(id).setName(name);
    }

    private static byte[] bytes(String value) {
        return value.getBytes(StandardCharsets.UTF_8);
    }

    private static <T> DefaultRedisScript<T> script(String name, Class<T> type) {
        DefaultRedisScript<T> script = new DefaultRedisScript<>();
        script.setLocation(new ClassPathResource(name));
        script.setResultType(type);
        return script;
    }
}
