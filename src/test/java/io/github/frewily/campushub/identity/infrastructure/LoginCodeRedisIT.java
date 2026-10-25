package io.github.frewily.campushub.identity.infrastructure;

import io.github.frewily.campushub.identity.application.LoginCodeService;
import io.github.frewily.campushub.identity.application.LoginCodeStore;
import io.github.frewily.campushub.identity.api.LoginCodeController;
import io.github.frewily.campushub.dto.Result;
import io.github.frewily.campushub.exception.BusinessException;
import io.github.frewily.campushub.exception.ErrorCode;
import io.github.frewily.campushub.utils.UserHolder;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.RedisCallback;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.net.ServerSocket;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;

import static io.github.frewily.campushub.utils.RedisConstants.*;
import static org.junit.jupiter.api.Assertions.*;

/** Own ephemeral localhost Redis only; never reads personal credentials or shared service settings. */
class LoginCodeRedisIT {
    @TempDir static Path directory;
    private static Process process;
    private static LettuceConnectionFactory factory;
    private static StringRedisTemplate redis;

    @BeforeAll static void startPrivateRedis() throws Exception {
        try {
            int port;
            try (ServerSocket socket = new ServerSocket(0)) { port = socket.getLocalPort(); }
            System.out.println("LOGIN_CODE_REDIS_PORT=" + port);
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

    @BeforeEach void isolateKeys() { UserHolder.removeUser(); }

    @AfterEach void cleanKeysAndThreadLocal() {
        try {
            redis.delete(codeKey("13800138001")); redis.delete(cooldownKey("13800138001"));
            redis.delete(codeKey("13800138002")); redis.delete(cooldownKey("13800138002"));
            redis.delete(codeKey("13800138003")); redis.delete(cooldownKey("13800138003"));
        } finally { UserHolder.removeUser(); }
    }

    @Test void sendsCodeWithTwoMinuteTtlAndCooldownWithSixtySecondTtlAndDoesNotExposeCode() {
        String phone = "13800138001";
        String generated = "381947";
        LoginCodeService service = new LoginCodeService(new RedisLoginCodeStore(redis), () -> generated);
        service.sendCode(phone);

        assertEquals(generated, redis.opsForValue().get(codeKey(phone)));
        long codeTtl = redis.getExpire(codeKey(phone), TimeUnit.SECONDS);
        assertTrue(codeTtl >= 115 && codeTtl <= 120, "retain 120-second TTL with bounded observation time");
        assertEquals("1", redis.opsForValue().get(cooldownKey(phone)));
        long cooldownTtl = redis.getExpire(cooldownKey(phone), TimeUnit.SECONDS);
        assertTrue(cooldownTtl >= 55 && cooldownTtl <= 60, "retain 60-second TTL with bounded observation time");
        Result response = new LoginCodeController(
                new LoginCodeService(new RedisLoginCodeStore(redis), () -> "805124"))
                .sendCode("13800138003");
        assertEquals("发送验证码成功", response.getData());
        assertFalse(response.toString().contains("805124"));
    }

    @Test void duplicateSendIsRateLimitedWithoutChangingExistingCodeOrTtl() {
        String phone = "13800138001";
        LoginCodeService service = new LoginCodeService(new RedisLoginCodeStore(redis), () -> "381947");
        service.sendCode(phone);
        // Age owned fixtures so an illicit refresh to 120s/60s cannot hide inside rounding.
        redis.expire(codeKey(phone), 30, TimeUnit.SECONDS);
        redis.expire(cooldownKey(phone), 20, TimeUnit.SECONDS);
        long before = redis.getExpire(codeKey(phone), TimeUnit.MILLISECONDS);
        long cooldownBefore = redis.getExpire(cooldownKey(phone), TimeUnit.MILLISECONDS);
        assertEquals(ErrorCode.RATE_LIMITED,
                assertThrows(BusinessException.class, () -> service.sendCode(phone)).getErrorCode());
        assertEquals("381947", redis.opsForValue().get(codeKey(phone)));
        long after = redis.getExpire(codeKey(phone), TimeUnit.MILLISECONDS);
        long cooldownAfter = redis.getExpire(cooldownKey(phone), TimeUnit.MILLISECONDS);
        assertTrue(after <= before && before - after < 5000, "rejection must not refresh existing code TTL");
        assertTrue(cooldownAfter <= cooldownBefore && cooldownBefore - cooldownAfter < 5000,
                "rejection must not refresh existing cooldown TTL");
    }

    @Test void phonesAreIsolatedAndExpiredCooldownCanBeDeletedBeforeOverwritingCode() {
        String first = "13800138001", second = "13800138002";
        LoginCodeService firstService = new LoginCodeService(new RedisLoginCodeStore(redis), () -> "381947");
        firstService.sendCode(first);
        new LoginCodeService(new RedisLoginCodeStore(redis), () -> "620315").sendCode(second);
        assertEquals("381947", redis.opsForValue().get(codeKey(first)));
        assertEquals("620315", redis.opsForValue().get(codeKey(second)));
        redis.delete(cooldownKey(first));
        new LoginCodeService(new RedisLoginCodeStore(redis), () -> "704286").sendCode(first);
        assertEquals("704286", redis.opsForValue().get(codeKey(first)));
        assertEquals("620315", redis.opsForValue().get(codeKey(second)));
    }

    @Test void saveFailureReleasesCooldownThroughRealAdapter() {
        String phone = "13800138001";
        RedisLoginCodeStore adapter = new RedisLoginCodeStore(redis);
        LoginCodeStore failingSave = new LoginCodeStore() {
            @Override public Boolean acquireCooldown(String value) { return adapter.acquireCooldown(value); }
            @Override public void saveCode(String value, String code) { throw new IllegalStateException("synthetic save failure"); }
            @Override public void releaseCooldown(String value) { adapter.releaseCooldown(value); }
        };
        assertThrows(IllegalStateException.class,
                () -> new LoginCodeService(failingSave, () -> "381947").sendCode(phone));
        assertFalse(Boolean.TRUE.equals(redis.hasKey(cooldownKey(phone))));
        assertFalse(Boolean.TRUE.equals(redis.hasKey(codeKey(phone))));
    }

    private String codeKey(String phone) { return LOGIN_CODE_KEY + phone; }
    private String cooldownKey(String phone) { return LOGIN_CODE_COOLDOWN_KEY + phone; }
}
