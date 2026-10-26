package io.github.frewily.campushub.identity.infrastructure;

import io.github.frewily.campushub.identity.application.LoginAccount;
import org.junit.jupiter.api.AfterAll;
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
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import static io.github.frewily.campushub.utils.RedisConstants.LOGIN_USER_KEY;
import static io.github.frewily.campushub.utils.RedisConstants.LOGIN_USER_TTL;
import static org.junit.jupiter.api.Assertions.*;

/** Own localhost Redis process; all generated session keys are tracked and only those keys are removed. */
class LoginSessionRedisIT {
    @TempDir static Path directory;
    private static Process process;
    private static LettuceConnectionFactory factory;
    private static StringRedisTemplate redis;
    private final List<String> ownedKeys = new ArrayList<>();

    @BeforeAll static void startPrivateRedis() throws Exception {
        try {
            int port;
            try (ServerSocket socket = new ServerSocket(0)) { port = socket.getLocalPort(); }
            System.out.println("LOGIN_SESSION_REDIS_PORT=" + port);
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
                try { if ("PONG".equals(redis.execute((RedisCallback<String>) c -> c.ping()))) return; }
                catch (RuntimeException notReady) { Thread.sleep(25); }
            }
            throw new IllegalStateException("Private Redis did not become ready");
        } catch (Exception | Error failure) { stopPrivateRedis(); throw failure; }
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

    @Test void writesRealHashWithTtlAndIsolatesTwoIssuedSessions() {
        RedisLoginSessionIssuer issuer = new RedisLoginSessionIssuer(redis);
        String first = issuer.issue(new LoginAccount(2101L, "session-it-a", "icon-a", "ACTIVE"));
        String second = issuer.issue(new LoginAccount(2101L, "session-it-b", "icon-b", "ACTIVE"));
        String firstKey = LOGIN_USER_KEY + first, secondKey = LOGIN_USER_KEY + second;
        ownedKeys.add(firstKey); ownedKeys.add(secondKey);

        assertEquals("2101", redis.opsForHash().get(firstKey, "id"));
        assertEquals("session-it-a", redis.opsForHash().get(firstKey, "nickName"));
        assertEquals("icon-a", redis.opsForHash().get(firstKey, "icon"));
        assertEquals("session-it-b", redis.opsForHash().get(secondKey, "nickName"));
        assertNotEquals(firstKey, secondKey);
        assertTrue(redis.getExpire(firstKey, TimeUnit.MINUTES) > LOGIN_USER_TTL - 2);
        assertTrue(redis.getExpire(secondKey, TimeUnit.MINUTES) > LOGIN_USER_TTL - 2);
        assertNull(redis.opsForHash().get(firstKey, "status"));
        assertNull(redis.opsForHash().get(firstKey, "phone"));
    }

    @Test void expiryWrapperFailureLeavesRealHashWithoutTtlThenCanBeCleanedUp() {
        String marker = "session-it-orphan-" + UUID.randomUUID();
        StringRedisTemplate failingExpiry = new StringRedisTemplate(factory) {
            @Override public Boolean expire(String key, long timeout, TimeUnit unit) {
                throw new IllegalStateException("test wrapper blocks expire");
            }
        };
        failingExpiry.afterPropertiesSet();
        assertThrows(IllegalStateException.class, () -> new RedisLoginSessionIssuer(failingExpiry)
                .issue(new LoginAccount(2102L, marker, null, "ACTIVE")));
        // This injects a client-wrapper failure, not a Redis ACL/command failure.
        String ownedKey = redis.keys(LOGIN_USER_KEY + "*").stream()
                .filter(key -> marker.equals(redis.opsForHash().get(key, "nickName"))).findFirst()
                .orElseThrow(() -> new AssertionError("owned wrapper-failure hash missing"));
        ownedKeys.add(ownedKey);
        assertEquals(-1L, redis.getExpire(ownedKey, TimeUnit.SECONDS));
        assertTrue(redis.expire(ownedKey, LOGIN_USER_TTL, TimeUnit.MINUTES));
        assertTrue(redis.getExpire(ownedKey, TimeUnit.MINUTES) > LOGIN_USER_TTL - 2);
        // StringRedisTemplate owns no pool; @AfterAll alone closes the shared owned factory/process.
    }

    @Test void actualPexpireAndExpireDenialLeavesHashWithoutTtlAndCanBeRestored() {
        Set<String> before = new HashSet<>(redis.keys(LOGIN_USER_KEY + "*"));
        try {
            expiryCommands(false);
            assertThrows(org.springframework.dao.DataAccessException.class,
                    () -> new RedisLoginSessionIssuer(redis).issue(new LoginAccount(2103L, "acl-session", "", "ACTIVE")));
            Set<String> created = new HashSet<>(redis.keys(LOGIN_USER_KEY + "*"));
            created.removeAll(before);
            ownedKeys.addAll(created);
            assertEquals(1, created.size(), "Hash was written before both expiry commands were rejected");
            String key = created.iterator().next();
            assertEquals("2103", redis.opsForHash().get(key, "id"));
            assertEquals(-1L, redis.getExpire(key, TimeUnit.SECONDS));
        } finally { expiryCommands(true); }
        String recovered = LOGIN_USER_KEY + new RedisLoginSessionIssuer(redis)
                .issue(new LoginAccount(2103L, "acl-session", "", "ACTIVE"));
        ownedKeys.add(recovered);
        assertTrue(redis.getExpire(recovered, TimeUnit.MINUTES) > LOGIN_USER_TTL - 2);
    }

    private void expiryCommands(boolean allow) {
        java.nio.charset.Charset utf8 = java.nio.charset.StandardCharsets.UTF_8;
        redis.execute((RedisCallback<Object>) connection -> connection.execute("ACL",
                "SETUSER".getBytes(utf8), "default".getBytes(utf8),
                (allow ? "+pexpire" : "-pexpire").getBytes(utf8),
                (allow ? "+expire" : "-expire").getBytes(utf8)));
    }

    @org.junit.jupiter.api.AfterEach void removeOnlyCreatedSessionKeys() {
        if (!ownedKeys.isEmpty()) redis.delete(ownedKeys);
        ownedKeys.clear();
    }
}
