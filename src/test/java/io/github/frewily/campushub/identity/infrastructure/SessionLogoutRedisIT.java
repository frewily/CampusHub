package io.github.frewily.campushub.identity.infrastructure;

import io.github.frewily.campushub.dto.UserDTO;
import io.github.frewily.campushub.identity.application.SessionLogoutService;
import io.github.frewily.campushub.utils.UserHolder;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.RedisCallback;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.net.ServerSocket;
import java.nio.file.Path;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import static io.github.frewily.campushub.utils.RedisConstants.LOGIN_USER_KEY;
import static org.junit.jupiter.api.Assertions.*;

/** Own ephemeral localhost Redis only; never reads personal credentials or shared service settings. */
class SessionLogoutRedisIT {
    @TempDir static Path directory;
    private static Process process;
    private static LettuceConnectionFactory factory;
    private static StringRedisTemplate redis;
    private String prefix;

    @BeforeAll static void startPrivateRedis() throws Exception {
        try {
            int port;
            try (ServerSocket socket = new ServerSocket(0)) { port = socket.getLocalPort(); }
            System.out.println("SESSION_LOGOUT_REDIS_PORT=" + port);
            process = new ProcessBuilder(System.getenv().getOrDefault("REDIS_SERVER_BINARY", "redis-server"),
                    "--bind", "127.0.0.1", "--port", Integer.toString(port), "--save", "", "--appendonly", "no",
                    "--dir", directory.toString()).redirectErrorStream(true)
                    .redirectOutput(directory.resolve("redis.log").toFile()).start();
            factory = new LettuceConnectionFactory("127.0.0.1", port);
            factory.afterPropertiesSet();
            redis = new StringRedisTemplate(factory); redis.afterPropertiesSet();
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

    @BeforeEach void isolateSyntheticTokens() {
        prefix = "phase7aa:" + UUID.randomUUID() + ":";
        cleanupIdentity();
    }

    @Test void removesOnlyCurrentTokenAndRepeatedLogoutIsIdempotent() {
        String current = prefix + "current", other = prefix + "other";
        redis.opsForHash().put(LOGIN_USER_KEY + current, "id", "1101");
        redis.opsForHash().put(LOGIN_USER_KEY + other, "id", "1101");
        redis.expire(LOGIN_USER_KEY + other, 120, TimeUnit.SECONDS);
        redis.opsForValue().set(prefix + "unrelated", "retained");
        UserHolder.saveUser(new UserDTO());
        RedisSessionTerminator terminator = new RedisSessionTerminator(redis);
        terminator.terminate(current);
        assertNull(UserHolder.getUser());
        assertTrue(redis.opsForHash().entries(LOGIN_USER_KEY + current).isEmpty());
        assertEquals("1101", redis.opsForHash().get(LOGIN_USER_KEY + other, "id"));
        UserHolder.saveUser(new UserDTO());
        terminator.terminate(current);
        assertNull(UserHolder.getUser());
        assertTrue(redis.opsForHash().entries(LOGIN_USER_KEY + current).isEmpty());
        assertEquals("1101", redis.opsForHash().get(LOGIN_USER_KEY + other, "id"));
        assertTrue(redis.getExpire(LOGIN_USER_KEY + other, TimeUnit.SECONDS) > 100);
        assertEquals("retained", redis.opsForValue().get(prefix + "unrelated"));
    }

    @Test void serviceTrimsTokenBeforeDeletingItsExistingSession() {
        String current = prefix + "current";
        redis.opsForHash().put(LOGIN_USER_KEY + current, "id", "1101");
        UserHolder.saveUser(new UserDTO());
        new SessionLogoutService(new RedisSessionTerminator(redis)).logout(" \t" + current + "  ");
        assertFalse(redis.hasKey(LOGIN_USER_KEY + current));
        assertNull(UserHolder.getUser());
    }

    @org.junit.jupiter.api.AfterEach void cleanupSyntheticIdentity() { cleanupIdentity(); }

    private void cleanupIdentity() {
        UserHolder.removeUser();
        if (prefix != null) {
            redis.delete(LOGIN_USER_KEY + prefix + "current");
            redis.delete(LOGIN_USER_KEY + prefix + "other");
            redis.delete(prefix + "unrelated");
        }
    }
}
