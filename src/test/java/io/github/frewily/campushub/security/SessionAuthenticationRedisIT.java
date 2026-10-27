package io.github.frewily.campushub.security;

import io.github.frewily.campushub.mapper.AccountAccessMapper;
import io.github.frewily.campushub.utils.UserHolder;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.RedisCallback;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.core.context.SecurityContextHolder;

import java.net.ServerSocket;
import java.nio.file.Path;
import java.util.Collections;
import java.util.Map;
import java.util.HashMap;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import static io.github.frewily.campushub.utils.RedisConstants.LOGIN_USER_KEY;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/** Real Redis and the actual filter; SQL access is an explicit mock, not a full HTTP server. */
class SessionAuthenticationRedisIT {
    @TempDir static Path directory;
    private static Process process;
    private static LettuceConnectionFactory factory;
    private static StringRedisTemplate redis;
    private String token;
    private AccountAccessMapper accounts;

    @BeforeAll static void start() throws Exception {
        try {
            int port;
            try (ServerSocket socket = new ServerSocket(0)) { port = socket.getLocalPort(); }
            System.out.println("SESSION_AUTHENTICATION_REDIS_PORT=" + port);
            process = new ProcessBuilder(System.getenv().getOrDefault("REDIS_SERVER_BINARY", "redis-server"),
                    "--bind", "127.0.0.1", "--port", Integer.toString(port), "--save", "", "--appendonly", "no",
                    "--dir", directory.toString()).redirectErrorStream(true)
                    .redirectOutput(directory.resolve("redis.log").toFile()).start();
            factory = new LettuceConnectionFactory("127.0.0.1", port); factory.afterPropertiesSet();
            redis = new StringRedisTemplate(factory); redis.afterPropertiesSet();
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
            while (process.isAlive() && System.nanoTime() < deadline) {
                try { if ("PONG".equals(redis.execute((RedisCallback<String>) c -> c.ping()))) return; }
                catch (RuntimeException notReady) { Thread.sleep(25); }
            }
            throw new IllegalStateException("private Redis did not start");
        } catch (Exception | Error failure) { stop(); throw failure; }
    }

    @AfterAll static void stop() throws Exception {
        try { if (factory != null) factory.destroy(); }
        finally {
            factory = null; redis = null;
            if (process != null) {
                Process owned = process; process = null; owned.destroy();
                if (!owned.waitFor(5, TimeUnit.SECONDS)) owned.destroyForcibly();
                assertTrue(owned.waitFor(5, TimeUnit.SECONDS) && !owned.isAlive());
            }
        }
    }

    @BeforeEach void fixture() {
        token = "synthetic-auth-" + UUID.randomUUID();
        accounts = mock(AccountAccessMapper.class);
        when(accounts.findAccountStatus(7L)).thenReturn("ACTIVE");
        when(accounts.findRoles(7L)).thenReturn(Collections.singletonList("USER"));
        UserHolder.removeUser(); SecurityContextHolder.clearContext();
    }

    @AfterEach void cleanup() {
        try { redis.delete(LOGIN_USER_KEY + token); }
        finally { UserHolder.removeUser(); SecurityContextHolder.clearContext(); }
    }

    @Test void historicalIdentityWithoutTtlIsRejectedInsteadOfReceivingAnewLifetime() throws Exception {
        String key = LOGIN_USER_KEY + token;
        redis.opsForHash().put(key, "id", "7");
        redis.opsForHash().put(key, "nickName", "historical");
        assertEquals(-1L, redis.getExpire(key, TimeUnit.MILLISECONDS));
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader("authorization", token);
        new RedisTokenAuthenticationFilter(redis, accounts).doFilter(request, new MockHttpServletResponse(), (a, b) -> {
            assertNull(UserHolder.getUser(), "a no-TTL identity must not authenticate");
            assertNull(SecurityContextHolder.getContext().getAuthentication());
        });
        assertFalse(redis.hasKey(key));
    }

    @Test void liveSessionKeepsRawIdAndRenewsBeforeExposingIdentity() throws Exception {
        seed("0007", 20);
        Map<Object, Object> before = new HashMap<>(redis.opsForHash().entries(key()));
        run(() -> {
            assertEquals(7L, UserHolder.getUser().getId());
            assertNotNull(SecurityContextHolder.getContext().getAuthentication());
            assertTrue(redis.getExpire(key(), TimeUnit.SECONDS) > 36000 * 60 - 60);
        });
        assertEquals(before, redis.opsForHash().entries(key()));
        clearContexts();
    }

    @Test void aSessionDeletedDuringSqlChecksCannotAuthenticate() throws Exception {
        seed("7", 60);
        doAnswer(call -> {
            redis.delete(key());
            return Collections.singletonList("USER");
        }).when(accounts).findRoles(7L);
        run(this::clearContexts);
        assertFalse(redis.hasKey(key()));
    }

    @Test void aReplacementIdentityIsNeitherAuthenticatedDeletedNorRenewed() throws Exception {
        for (boolean persistent : new boolean[]{false, true}) {
            seed("7", 60);
            final long[] replacementTtl = {0};
            doAnswer(call -> {
                redis.opsForHash().put(key(), "id", "8");
                redis.opsForHash().put(key(), "nickName", "replacement");
                if (persistent) redis.persist(key()); else redis.expire(key(), 45, TimeUnit.SECONDS);
                replacementTtl[0] = redis.getExpire(key(), TimeUnit.MILLISECONDS);
                return Collections.singletonList("USER");
            }).when(accounts).findRoles(7L);
            run(this::clearContexts);
            assertEquals("8", redis.opsForHash().get(key(), "id"));
            assertEquals("replacement", redis.opsForHash().get(key(), "nickName"));
            long after = redis.getExpire(key(), TimeUnit.MILLISECONDS);
            if (persistent) assertEquals(-1L, after);
            else assertTrue(after <= replacementTtl[0] && replacementTtl[0] - after < 5000);
        }
    }

    @Test void replacementWithAnotherRedisTypeIsNotDeletedOrAuthenticated() throws Exception {
        seed("7", 60);
        doAnswer(call -> {
            redis.delete(key());
            redis.opsForValue().set(key(), "retained-other-data", 45, TimeUnit.SECONDS);
            return Collections.singletonList("USER");
        }).when(accounts).findRoles(7L);
        run(this::clearContexts);
        assertEquals("retained-other-data", redis.opsForValue().get(key()));
        assertTrue(redis.getExpire(key(), TimeUnit.SECONDS) <= 45);
    }

    @Test void missingTtlIsRejectedEvenWhenCleanupIsDeniedAndRemovedAfterRecovery() throws Exception {
        seed("7", 0);
        try {
            command(false, "del");
            run(this::clearContexts);
            assertEquals("7", redis.opsForHash().get(key(), "id"));
            assertEquals(-1L, redis.getExpire(key(), TimeUnit.MILLISECONDS));
        } finally { command(true, "del"); }
        run(this::clearContexts);
        assertFalse(redis.hasKey(key()));
    }

    @Test void actualRenewalFailureDoesNotRunProtectedChainOrLeakContexts() throws Exception {
        seed("7", 60);
        final boolean[] invoked = {false};
        Map<Object, Object> before = new HashMap<>(redis.opsForHash().entries(key()));
        long beforeTtl = redis.getExpire(key(), TimeUnit.MILLISECONDS);
        try {
            command(false, "pexpire");
            assertThrows(org.springframework.dao.DataAccessException.class, () -> run(() -> invoked[0] = true));
            assertFalse(invoked[0]);
            clearContexts();
            assertEquals(before, redis.opsForHash().entries(key()));
            long after = redis.getExpire(key(), TimeUnit.MILLISECONDS);
            assertTrue(after > 0 && after <= beforeTtl);
        } finally { command(true, "pexpire"); }
    }

    private void seed(String id, long ttlSeconds) {
        redis.opsForHash().put(key(), "id", id);
        redis.opsForHash().put(key(), "nickName", "profile");
        if (ttlSeconds > 0) redis.expire(key(), ttlSeconds, TimeUnit.SECONDS); else redis.persist(key());
    }

    private String key() { return LOGIN_USER_KEY + token; }

    private void run(Runnable chain) throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader("authorization", "  " + token + "  ");
        new RedisTokenAuthenticationFilter(redis, accounts).doFilter(request, new MockHttpServletResponse(), (a, b) -> chain.run());
        clearContexts();
    }

    private void clearContexts() {
        assertNull(UserHolder.getUser());
        assertNull(SecurityContextHolder.getContext().getAuthentication());
    }

    private void command(boolean allow, String name) {
        java.nio.charset.Charset utf8 = java.nio.charset.StandardCharsets.UTF_8;
        redis.execute((RedisCallback<Object>) connection -> connection.execute("ACL", "SETUSER".getBytes(utf8),
                "default".getBytes(utf8), ((allow ? "+" : "-") + name).getBytes(utf8)));
    }
}
