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
import java.util.Collections;
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

    @Test void expiryAndCleanupDenialLeavesOnlyAnIdentityFreePlaceholder() {
        Set<String> before = new HashSet<>(redis.keys(LOGIN_USER_KEY + "*"));
        try {
            commands(false, "pexpire", "expire", "del");
            assertThrows(IllegalStateException.class,
                    () -> new RedisLoginSessionIssuer(redis).issue(new LoginAccount(2102L, "not-published", "", "ACTIVE")));
            Set<String> created = new HashSet<>(redis.keys(LOGIN_USER_KEY + "*"));
            created.removeAll(before);
            ownedKeys.addAll(created);
            assertEquals(1, created.size());
            String key = created.iterator().next();
            assertEquals(Collections.singletonMap("nickName", ""), redis.opsForHash().entries(key));
            assertNull(redis.opsForHash().get(key, "id"));
            assertEquals(-1L, redis.getExpire(key, TimeUnit.SECONDS));
        } finally { commands(true, "pexpire", "expire", "del"); }
    }

    @Test void actualExpiryDenialDoesNotPublishAnySessionAndCanBeRestored() {
        Set<String> before = new HashSet<>(redis.keys(LOGIN_USER_KEY + "*"));
        try {
            expiryCommands(false);
            assertThrows(RuntimeException.class,
                    () -> new RedisLoginSessionIssuer(redis).issue(new LoginAccount(2103L, "acl-session", "", "ACTIVE")));
            Set<String> created = new HashSet<>(redis.keys(LOGIN_USER_KEY + "*"));
            created.removeAll(before);
            ownedKeys.addAll(created);
            assertTrue(created.isEmpty(), "expiry rejection must not leave a published session Hash");
        } finally { expiryCommands(true); }
        String recovered = LOGIN_USER_KEY + new RedisLoginSessionIssuer(redis)
                .issue(new LoginAccount(2103L, "acl-session", "", "ACTIVE"));
        ownedKeys.add(recovered);
        assertTrue(redis.getExpire(recovered, TimeUnit.MINUTES) > LOGIN_USER_TTL - 2);
    }

    private void expiryCommands(boolean allow) {
        commands(allow, "pexpire", "expire");
    }

    private void commands(boolean allow, String... names) {
        java.nio.charset.Charset utf8 = java.nio.charset.StandardCharsets.UTF_8;
        byte[][] args = new byte[names.length + 2][];
        args[0] = "SETUSER".getBytes(utf8); args[1] = "default".getBytes(utf8);
        for (int i = 0; i < names.length; i++) args[i + 2] = ((allow ? "+" : "-") + names[i]).getBytes(utf8);
        redis.execute((RedisCallback<Object>) connection -> connection.execute("ACL", args));
    }

    @Test void collisionDoesNotOverwriteOrRefreshAnExistingSession() {
        String key = LOGIN_USER_KEY + UUID.randomUUID().toString().replace("-", "");
        ownedKeys.add(key);
        redis.opsForHash().put(key, "id", "retained");
        redis.expire(key, 60, TimeUnit.SECONDS);
        long before = redis.getExpire(key, TimeUnit.MILLISECONDS);
        assertEquals(0L, publish(key, "2160000000", "9007199254748993", "replacement", ""));
        assertEquals(Collections.singletonMap("id", "retained"), redis.opsForHash().entries(key));
        long after = redis.getExpire(key, TimeUnit.MILLISECONDS);
        assertTrue(after <= before && before - after < 5000);
    }

    @Test void invalidPublicationArgumentsDoNotCreateAnyKey() {
        String key = LOGIN_USER_KEY + UUID.randomUUID().toString().replace("-", "");
        ownedKeys.add(key);
        for (String ttl : new String[]{"0", "-1", "1.5", "not-a-number", "9007199254740992"}) {
            assertEquals(-2L, publish(key, ttl, "2104", "nick", ""));
            assertFalse(redis.hasKey(key));
        }
        assertEquals(-2L, publish(key, "2160000000", "2104", "missing-icon"));
        assertFalse(redis.hasKey(key));
    }

    private Long publish(String key, Object... args) {
        org.springframework.data.redis.core.script.DefaultRedisScript<Long> script =
                new org.springframework.data.redis.core.script.DefaultRedisScript<>();
        script.setLocation(new org.springframework.core.io.ClassPathResource("login-session-publish.lua"));
        script.setResultType(Long.class);
        return redis.execute(script, Collections.singletonList(key), args);
    }

    @org.junit.jupiter.api.AfterEach void removeOnlyCreatedSessionKeys() {
        if (!ownedKeys.isEmpty()) redis.delete(ownedKeys);
        ownedKeys.clear();
    }
}
