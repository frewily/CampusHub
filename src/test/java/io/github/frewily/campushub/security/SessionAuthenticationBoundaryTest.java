package io.github.frewily.campushub.security;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

import static org.junit.jupiter.api.Assertions.*;

class SessionAuthenticationBoundaryTest {
    @Test void luaMustBeSingleKeyAndCompareIdentityAsRawString() throws Exception {
        Path path = Paths.get("src/main/resources/session-authenticate.lua");
        assertTrue(Files.isRegularFile(path), "session-authenticate.lua must be a classpath resource");
        String lua = new String(Files.readAllBytes(path), StandardCharsets.UTF_8);
        assertTrue(lua.contains("KEYS[1]"));
        assertTrue(lua.contains("ARGV[1]"));
        assertTrue(lua.contains("ARGV[2]"));
        assertTrue(lua.contains("TYPE"));
        assertTrue(lua.contains("HGET"));
        assertTrue(lua.contains("PTTL"));
        assertTrue(lua.contains("PEXPIRE"));
        assertTrue(lua.contains("DEL"));
        assertTrue(lua.contains("redis.call('HGET', KEYS[1], 'id') ~= ARGV[1]"));
        assertFalse(lua.matches("(?s).*tonumber\\s*\\(\\s*ARGV\\[1\\].*"), "identity must never be converted to a number");
        assertFalse(lua.contains("KEYS[2]"), "authentication script must use exactly one key");
    }

    @Test void filterMustExecuteScriptBeforeSavingEitherAuthenticationContext() throws Exception {
        Path path = Paths.get("src/main/java/io/github/frewily/campushub/security/RedisTokenAuthenticationFilter.java");
        String source = new String(Files.readAllBytes(path), StandardCharsets.UTF_8);
        int execute = source.indexOf("stringRedisTemplate.execute(");
        int saveUser = source.indexOf("UserHolder.saveUser(user)");
        int setAuthentication = source.indexOf("setAuthentication(authentication)");
        assertTrue(execute >= 0, "filter must execute the session authentication script");
        assertTrue(saveUser > execute, "UserHolder must be populated after script execution");
        assertTrue(setAuthentication > execute, "SecurityContext must be populated after script execution");
        assertTrue(source.contains("DefaultRedisScript<Long>"));
        assertTrue(source.contains("setResultType(Long.class)"));
        assertTrue(source.contains("session-authenticate.lua"));
        assertTrue(source.contains("Collections.singletonList(tokenKey)"));
        assertTrue(source.contains("String.valueOf(userMap.get(\"id\"))"));
        assertTrue(source.contains("String.valueOf(TimeUnit.MINUTES.toMillis(RedisConstants.LOGIN_USER_TTL))"));
        assertFalse(source.contains("stringRedisTemplate.expire("), "Java must not renew TTL outside the Lua script");
    }
}
