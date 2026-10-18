package io.github.frewily.campushub.engagement.infrastructure;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.connection.BitFieldSubCommands;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.time.LocalDate;
import java.util.Collections;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class RedisCheckInStoreTest {
    private ValueOperations<String, String> values;
    private RedisCheckInStore store;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        StringRedisTemplate redis = mock(StringRedisTemplate.class);
        values = mock(ValueOperations.class);
        when(redis.opsForValue()).thenReturn(values);
        store = new RedisCheckInStore(redis);
    }

    @Test
    void preservesTheLegacyKeyAndZeroBasedOffsetWithoutAddingExpiry() {
        store.markSigned(9007199254740993L, LocalDate.of(2026, 10, 31));
        verify(values).setBit("sign:9007199254740993:202610", 30L, true);
        verifyNoMoreInteractions(values);
    }

    @Test
    void nullReplyRemainsZero() {
        when(values.bitField(anyString(), any(BitFieldSubCommands.class))).thenReturn(null);
        assertEquals(0L, store.signedDays(21L, LocalDate.of(2026, 10, 18)));
    }

    @Test
    void emptyReplyRemainsZero() {
        when(values.bitField(anyString(), any(BitFieldSubCommands.class))).thenReturn(Collections.emptyList());
        assertEquals(0L, store.signedDays(21L, LocalDate.of(2026, 10, 18)));
    }

    @Test
    void nullFirstItemRemainsZero() {
        when(values.bitField(anyString(), any(BitFieldSubCommands.class))).thenReturn(Collections.singletonList(null));
        assertEquals(0L, store.signedDays(21L, LocalDate.of(2026, 10, 18)));
    }

    @Test
    void preservesTheUnsigned31BitResult() {
        when(values.bitField(eq("sign:21:202610"), any(BitFieldSubCommands.class)))
                .thenReturn(Collections.singletonList(2147483647L));
        assertEquals(2147483647L, store.signedDays(21L, LocalDate.of(2026, 10, 31)));
    }
}
