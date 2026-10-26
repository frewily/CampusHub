package io.github.frewily.campushub.identity.infrastructure;

import io.github.frewily.campushub.dto.UserDTO;
import io.github.frewily.campushub.identity.application.LoginAccount;
import io.github.frewily.campushub.utils.UserHolder;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;

import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import static io.github.frewily.campushub.utils.RedisConstants.LOGIN_USER_KEY;
import static io.github.frewily.campushub.utils.RedisConstants.LOGIN_USER_TTL;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class RedisLoginSessionIssuerTest {
    private static final long LOGIN_TTL_MILLIS = TimeUnit.MINUTES.toMillis(LOGIN_USER_TTL);

    @Mock StringRedisTemplate redis;

    @Test
    void publishesOnlyMappedUserFieldsThroughOneScriptAndReturnsUuidToken() throws Exception {
        when(redis.execute(any(DefaultRedisScript.class), anyList(), anyString(), anyString(), anyString(), anyString())).thenReturn(1L);
        RedisLoginSessionIssuer issuer = new RedisLoginSessionIssuer(redis);

        String token = issuer.issue(new LoginAccount(9007199254748993L, "nick", "", "ACTIVE"));

        assertTrue(token.matches("[0-9a-fA-F]{32}"));
        ArgumentCaptor<DefaultRedisScript> script = ArgumentCaptor.forClass(DefaultRedisScript.class);
        ArgumentCaptor<List<String>> keys = ArgumentCaptor.forClass(List.class);
        Object[] args = capturedArguments(script, keys);
        assertEquals(Long.class, script.getValue().getResultType());
        assertEquals(new String(java.nio.file.Files.readAllBytes(java.nio.file.Paths.get(
                "src/main/resources/login-session-publish.lua")), java.nio.charset.StandardCharsets.UTF_8),
                script.getValue().getScriptAsString());
        assertEquals(Collections.singletonList(LOGIN_USER_KEY + token), keys.getValue());
        assertArrayEquals(new Object[]{String.valueOf(LOGIN_TTL_MILLIS), "9007199254748993", "nick", ""}, args);
        verify(redis, never()).opsForHash();
        verify(redis, never()).expire(anyString(), anyLong(), any(TimeUnit.class));
        verify(redis, never()).delete(anyString());
    }

    @Test
    void nullableDisplayFieldsMatchOriginalHutoolMappingAndNullBecomesEmptyString() {
        when(redis.execute(any(DefaultRedisScript.class), anyList(), anyString(), anyString(), anyString(), anyString())).thenReturn(1L);
        LoginAccount account = new LoginAccount(9L, null, null, "ACTIVE");
        UserDTO originalDto = cn.hutool.core.bean.BeanUtil.copyProperties(account, UserDTO.class);
        Map<String, Object> originalMap = cn.hutool.core.bean.BeanUtil.beanToMap(originalDto, new HashMap<>(),
                cn.hutool.core.bean.copier.CopyOptions.create().setIgnoreNullValue(true)
                        .setFieldValueEditor((field, value) -> value != null ? value.toString() : ""));

        new RedisLoginSessionIssuer(redis).issue(account);

        Object[] args = capturedArguments();
        Map<String, Object> mappedArgs = new HashMap<>();
        mappedArgs.put("id", args[1]);
        mappedArgs.put("nickName", args[2]);
        mappedArgs.put("icon", args[3]);
        assertEquals(originalMap, mappedArgs);
        assertArrayEquals(new Object[]{String.valueOf(LOGIN_TTL_MILLIS), "9", "", ""}, args);
    }

    @Test
    void nullIconKeepsTheOriginalEditorMapping() {
        when(redis.execute(any(DefaultRedisScript.class), anyList(), anyString(), anyString(), anyString(), anyString())).thenReturn(1L);

        String token = new RedisLoginSessionIssuer(redis).issue(new LoginAccount(8L, "name", null, "ACTIVE"));

        assertTrue(token.matches("[0-9a-fA-F]{32}"));
        Object[] args = capturedArguments();
        assertArrayEquals(new Object[]{String.valueOf(LOGIN_TTL_MILLIS), "8", "name", ""}, args);
    }

    @Test
    void argumentsDoNotIncludeWhitelistAndAmbientIdentityIsUnchanged() {
        when(redis.execute(any(DefaultRedisScript.class), anyList(), anyString(), anyString(), anyString(), anyString())).thenReturn(1L);
        UserDTO ambient = new UserDTO();
        ambient.setId(17L);
        UserHolder.saveUser(ambient);
        try {
            new RedisLoginSessionIssuer(redis).issue(new LoginAccount(18L, "other", "", "ACTIVE"));
            assertSame(ambient, UserHolder.getUser());
            Object[] args = capturedArguments();
            assertArrayEquals(new Object[]{String.valueOf(LOGIN_TTL_MILLIS), "18", "other", ""}, args);
            assertEquals(4, args.length);
        } finally {
            UserHolder.removeUser();
        }
    }

    @Test
    void onlyOneResultReturnsTokenAndEveryOtherResultFailsClosed() {
        for (Long result : Arrays.asList(0L, -1L, -2L, 2L, null)) {
            when(redis.execute(any(DefaultRedisScript.class), anyList(), anyString(), anyString(), anyString(), anyString())).thenReturn(result);
            IllegalStateException failure = assertThrows(IllegalStateException.class,
                    () -> new RedisLoginSessionIssuer(redis).issue(new LoginAccount(1L, "n", "i", "ACTIVE")));
            assertEquals("登录会话签发未完成", failure.getMessage());
        }
        verify(redis, times(5)).execute(any(DefaultRedisScript.class), anyList(), anyString(), anyString(), anyString(), anyString());
        verify(redis, never()).delete(anyString());
    }

    @Test
    void executionExceptionPropagatesUnchangedAndDoesNotAttemptCleanupOutsideScript() {
        IllegalStateException expected = new IllegalStateException("script execution failed");
        when(redis.execute(any(DefaultRedisScript.class), anyList(), anyString(), anyString(), anyString(), anyString())).thenThrow(expected);

        IllegalStateException actual = assertThrows(IllegalStateException.class,
                () -> new RedisLoginSessionIssuer(redis).issue(new LoginAccount(1L, "n", "i", "ACTIVE")));

        assertSame(expected, actual);
        verify(redis).execute(any(DefaultRedisScript.class), anyList(), anyString(), anyString(), anyString(), anyString());
        verify(redis, never()).delete(anyString());
        verify(redis, never()).opsForHash();
        verify(redis, never()).expire(anyString(), anyLong(), any(TimeUnit.class));
    }

    private Object[] capturedArguments() {
        return capturedArguments(ArgumentCaptor.forClass(DefaultRedisScript.class), ArgumentCaptor.forClass(List.class));
    }

    private Object[] capturedArguments(ArgumentCaptor<DefaultRedisScript> script, ArgumentCaptor<List<String>> keys) {
        ArgumentCaptor<String> ttl = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<String> id = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<String> nickName = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<String> icon = ArgumentCaptor.forClass(String.class);
        verify(redis).execute(script.capture(), keys.capture(), ttl.capture(), id.capture(), nickName.capture(), icon.capture());
        return new Object[]{ttl.getValue(), id.getValue(), nickName.getValue(), icon.getValue()};
    }
}
