package io.github.frewily.campushub.identity.infrastructure;

import io.github.frewily.campushub.identity.application.LoginAccount;
import io.github.frewily.campushub.dto.UserDTO;
import io.github.frewily.campushub.utils.UserHolder;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.HashOperations;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.util.Map;
import java.util.HashMap;
import java.util.concurrent.TimeUnit;

import static io.github.frewily.campushub.utils.RedisConstants.LOGIN_USER_KEY;
import static io.github.frewily.campushub.utils.RedisConstants.LOGIN_USER_TTL;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@SuppressWarnings("unchecked")
class RedisLoginSessionIssuerTest {
    @Mock StringRedisTemplate redis;
    @Mock HashOperations<String, Object, Object> hashes;

    @Test
    void writesOnlyUserDtoFieldsAsStringsBeforeApplyingMinuteTtl() {
        when(redis.opsForHash()).thenReturn(hashes);
        when(redis.expire(anyString(), eq(LOGIN_USER_TTL), eq(TimeUnit.MINUTES))).thenReturn(true);
        RedisLoginSessionIssuer issuer = new RedisLoginSessionIssuer(redis);

        String token = issuer.issue(new LoginAccount(9007199254748993L, "nick", "", "ACTIVE"));

        assertTrue(token.matches("[0-9a-fA-F]{32}"));
        InOrder order = inOrder(redis, hashes);
        ArgumentCaptor<String> key = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<Map<String, Object>> data = ArgumentCaptor.forClass(Map.class);
        order.verify(redis).opsForHash();
        order.verify(hashes).putAll(key.capture(), data.capture());
        order.verify(redis).expire(key.getValue(), LOGIN_USER_TTL, TimeUnit.MINUTES);
        assertTrue(key.getValue().startsWith(LOGIN_USER_KEY));
        assertTrue(key.getValue().endsWith(token));
        Map<String, Object> expected = new HashMap<>();
        expected.put("id", "9007199254748993"); expected.put("nickName", "nick"); expected.put("icon", "");
        assertEquals(expected, data.getValue());
        assertFalse(data.getValue().containsKey("status"));
        assertFalse(data.getValue().containsKey("phone"));
        assertFalse(data.getValue().containsKey("password"));
        verify(redis, never()).delete(anyString());
    }

    @Test
    void legacyEditorTurnsNullIconIntoEmptyStringAndFalseOrNullExpiryStillReturnsToken() {
        when(redis.opsForHash()).thenReturn(hashes);
        RedisLoginSessionIssuer issuer = new RedisLoginSessionIssuer(redis);
        when(redis.expire(anyString(), eq(LOGIN_USER_TTL), eq(TimeUnit.MINUTES))).thenReturn(false, null);
        for (int i = 0; i < 2; i++) {
            String token = issuer.issue(new LoginAccount(8L, "name", null, "ACTIVE"));
            assertTrue(token.matches("[0-9a-fA-F]{32}"));
        }
        ArgumentCaptor<Map<String, Object>> data = ArgumentCaptor.forClass(Map.class);
        verify(hashes, times(2)).putAll(anyString(), data.capture());
        Map<String, Object> expected = new HashMap<>();
        expected.put("id", "8"); expected.put("nickName", "name"); expected.put("icon", "");
        assertEquals(expected, data.getAllValues().get(0));
        assertEquals(expected, data.getAllValues().get(1));
    }

    @Test
    void nullableDisplayFieldsMatchTheOriginalHutoolMappingExactly() {
        when(redis.opsForHash()).thenReturn(hashes);
        LoginAccount account = new LoginAccount(9L, null, null, "ACTIVE");
        UserDTO originalDto = cn.hutool.core.bean.BeanUtil.copyProperties(account, UserDTO.class);
        Map<String, Object> originalMap = cn.hutool.core.bean.BeanUtil.beanToMap(originalDto, new HashMap<>(),
                cn.hutool.core.bean.copier.CopyOptions.create().setIgnoreNullValue(true)
                        .setFieldValueEditor((field, value) -> value != null ? value.toString() : ""));
        new RedisLoginSessionIssuer(redis).issue(account);
        ArgumentCaptor<Map<String, Object>> data = ArgumentCaptor.forClass(Map.class);
        verify(hashes).putAll(anyString(), data.capture());
        assertEquals(originalMap, data.getValue());
        Map<String, Object> expected = new HashMap<>();
        expected.put("id", "9"); expected.put("nickName", ""); expected.put("icon", "");
        assertEquals(expected, data.getValue());
    }

    @Test
    void issuingSessionDoesNotReplaceAmbientRequestIdentity() {
        when(redis.opsForHash()).thenReturn(hashes);
        UserDTO ambient = new UserDTO(); ambient.setId(17L);
        UserHolder.saveUser(ambient);
        try {
            new RedisLoginSessionIssuer(redis).issue(new LoginAccount(18L, "other", "", "ACTIVE"));
            assertSame(ambient, UserHolder.getUser());
        } finally { UserHolder.removeUser(); }
    }

    @Test
    void putAllFailureDoesNotAttemptExpiry() {
        when(redis.opsForHash()).thenReturn(hashes);
        doThrow(new IllegalStateException("write failed")).when(hashes).putAll(anyString(), anyMap());
        RedisLoginSessionIssuer issuer = new RedisLoginSessionIssuer(redis);

        assertThrows(IllegalStateException.class, () -> issuer.issue(new LoginAccount(1L, "n", "i", "ACTIVE")));
        verify(redis, never()).expire(anyString(), anyLong(), any(TimeUnit.class));
        verify(redis, never()).delete(anyString());
    }

    @Test
    void expiryFailurePropagatesWithoutDeletingTheWrittenHash() {
        when(redis.opsForHash()).thenReturn(hashes);
        when(redis.expire(anyString(), eq(LOGIN_USER_TTL), eq(TimeUnit.MINUTES))).thenThrow(new IllegalStateException("expire failed"));
        RedisLoginSessionIssuer issuer = new RedisLoginSessionIssuer(redis);

        assertThrows(IllegalStateException.class, () -> issuer.issue(new LoginAccount(1L, "n", "i", "ACTIVE")));
        verify(hashes).putAll(anyString(), anyMap());
        verify(redis).expire(anyString(), eq(LOGIN_USER_TTL), eq(TimeUnit.MINUTES));
        verify(redis, never()).delete(anyString());
    }
}
