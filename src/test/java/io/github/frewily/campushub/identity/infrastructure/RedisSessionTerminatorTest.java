package io.github.frewily.campushub.identity.infrastructure;

import io.github.frewily.campushub.dto.UserDTO;
import io.github.frewily.campushub.identity.application.SessionLogoutService;
import io.github.frewily.campushub.utils.UserHolder;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;

import static io.github.frewily.campushub.utils.RedisConstants.LOGIN_USER_KEY;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class RedisSessionTerminatorTest {
    private final StringRedisTemplate redis = mock(StringRedisTemplate.class);
    private final RedisSessionTerminator terminator = new RedisSessionTerminator(redis);

    @AfterEach void clearIdentity() { UserHolder.removeUser(); }

    @Test void clearsIdentityOnlyAfterSuccessfulDelete() {
        UserDTO identity = new UserDTO();
        UserHolder.saveUser(identity);
        when(redis.delete(LOGIN_USER_KEY + "current-token")).thenAnswer(call -> {
            assertSame(identity, UserHolder.getUser(), "delete must happen before local identity cleanup");
            return true;
        });
        terminator.terminate("current-token");
        assertNull(UserHolder.getUser());
        verify(redis).delete(LOGIN_USER_KEY + "current-token");
        verifyNoMoreInteractions(redis);
    }

    @Test void deletesOnlySuppliedSessionAndClearsIdentityEvenWhenDeleteReturnsFalseOrNull() {
        for (Boolean result : new Boolean[]{false, null}) {
            UserHolder.saveUser(new UserDTO());
            when(redis.delete(LOGIN_USER_KEY + "current-token")).thenReturn(result);
            terminator.terminate("current-token");
            assertNull(UserHolder.getUser());
        }
        verify(redis, times(2)).delete(LOGIN_USER_KEY + "current-token");
        verifyNoMoreInteractions(redis);
    }

    @Test void redisFailurePropagatesBeforeIdentityIsCleared() {
        UserDTO identity = new UserDTO();
        UserHolder.saveUser(identity);
        when(redis.delete(LOGIN_USER_KEY + "current-token")).thenThrow(new IllegalStateException("synthetic"));
        assertThrows(IllegalStateException.class, () -> terminator.terminate("current-token"));
        assertSame(identity, UserHolder.getUser());
        verify(redis).delete(LOGIN_USER_KEY + "current-token");
    }

    @Test void serviceLogoutTrimsLegacyTokenAndAdapterClearsCurrentIdentity() {
        UserHolder.saveUser(new UserDTO());
        new SessionLogoutService(terminator).logout(" token-value ");
        verify(redis).delete(LOGIN_USER_KEY + "token-value");
        assertNull(UserHolder.getUser());
    }
}
