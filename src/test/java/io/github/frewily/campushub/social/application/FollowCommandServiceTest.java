package io.github.frewily.campushub.social.application;

import io.github.frewily.campushub.exception.BusinessException;
import io.github.frewily.campushub.exception.ErrorCode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class FollowCommandServiceTest {
    private static final long ACTOR = 1L;
    private static final long TARGET = 9L;
    private static final long FIXED_MILLIS = 1_792_598_400_123L;

    @Mock private FollowCommandRepository repository;
    @Mock private FollowProjectionWriter projection;
    private FollowCommandService service;

    @BeforeEach
    void setUp() {
        service = new FollowCommandService(repository, projection,
                Clock.fixed(Instant.ofEpochMilli(FIXED_MILLIS), ZoneOffset.UTC));
    }

    /** Migrated regression: repeated requests remain idempotent and repair the Redis projection. */
    @Test
    void shouldTreatRepeatedFollowRequestAsIdempotentAndRepairRedisProjection() {
        when(repository.exists(ACTOR, TARGET)).thenReturn(true);

        service.follow(ACTOR, TARGET, true);

        verify(repository).exists(ACTOR, TARGET);
        verify(repository, never()).create(anyLong(), anyLong());
        verify(projection).add(ACTOR, TARGET, FIXED_MILLIS);
        verifyNoMoreInteractions(repository, projection);
    }

    /** Migrated regression: self-follow is rejected before touching either port. */
    @Test
    void shouldRejectFollowingTheCurrentUser() {
        BusinessException failure = assertThrows(BusinessException.class,
                () -> service.follow(ACTOR, ACTOR, true));

        assertEquals(ErrorCode.CONFLICT, failure.getErrorCode());
        verifyNoInteractions(repository, projection);
    }

    @Test
    void successfulCreateWritesProjectionUsingFixedClock() {
        when(repository.exists(ACTOR, TARGET)).thenReturn(false);
        when(repository.create(ACTOR, TARGET)).thenReturn(true);

        service.follow(ACTOR, TARGET, true);

        verify(repository).exists(ACTOR, TARGET);
        verify(repository).create(ACTOR, TARGET);
        verify(projection).add(ACTOR, TARGET, FIXED_MILLIS);
        verifyNoMoreInteractions(repository, projection);
    }

    @Test
    void falseCreateResultDoesNotWriteProjection() {
        when(repository.exists(ACTOR, TARGET)).thenReturn(false);
        when(repository.create(ACTOR, TARGET)).thenReturn(false);

        service.follow(ACTOR, TARGET, true);

        verify(repository).exists(ACTOR, TARGET);
        verify(repository).create(ACTOR, TARGET);
        verifyNoInteractions(projection);
    }

    @Test
    void successfulUnfollowRemovesProjection() {
        when(repository.delete(ACTOR, TARGET)).thenReturn(true);

        service.follow(ACTOR, TARGET, false);

        verify(repository).delete(ACTOR, TARGET);
        verify(projection).remove(ACTOR, TARGET);
        verifyNoMoreInteractions(repository, projection);
    }

    @Test
    void repeatedUnfollowDoesNotRepairAStaleProjection() {
        when(repository.delete(ACTOR, TARGET)).thenReturn(false);

        service.follow(ACTOR, TARGET, false);

        verify(repository).delete(ACTOR, TARGET);
        verifyNoInteractions(projection);
    }

    @Test
    void repositoryFailurePropagatesWithoutProjectionWrite() {
        IllegalStateException failure = new IllegalStateException("synthetic SQL failure");
        when(repository.exists(ACTOR, TARGET)).thenThrow(failure);

        assertSame(failure, assertThrows(IllegalStateException.class,
                () -> service.follow(ACTOR, TARGET, true)));
        verifyNoInteractions(projection);
    }

    @Test
    void projectionFailurePropagatesAfterSuccessfulDatabaseCreate() {
        when(repository.exists(ACTOR, TARGET)).thenReturn(false);
        when(repository.create(ACTOR, TARGET)).thenReturn(true);
        IllegalStateException failure = new IllegalStateException("synthetic Redis failure");
        doThrow(failure).when(projection).add(ACTOR, TARGET, FIXED_MILLIS);

        assertSame(failure, assertThrows(IllegalStateException.class,
                () -> service.follow(ACTOR, TARGET, true)));
        verify(repository).create(ACTOR, TARGET);
        verify(projection).add(ACTOR, TARGET, FIXED_MILLIS);
    }

    @Test
    void createFailurePropagatesWithoutProjectionWrite() {
        when(repository.exists(ACTOR, TARGET)).thenReturn(false);
        IllegalStateException failure = new IllegalStateException("synthetic SQL insert failure");
        when(repository.create(ACTOR, TARGET)).thenThrow(failure);
        assertSame(failure, assertThrows(IllegalStateException.class,
                () -> service.follow(ACTOR, TARGET, true)));
        verifyNoInteractions(projection);
    }

    @Test
    void deleteFailurePropagatesWithoutProjectionRemoval() {
        IllegalStateException failure = new IllegalStateException("synthetic SQL delete failure");
        when(repository.delete(ACTOR, TARGET)).thenThrow(failure);

        assertSame(failure, assertThrows(IllegalStateException.class,
                () -> service.follow(ACTOR, TARGET, false)));
        verifyNoInteractions(projection);
    }

    @Test
    void projectionRemovalFailurePropagatesAfterSuccessfulDatabaseDelete() {
        when(repository.delete(ACTOR, TARGET)).thenReturn(true);
        IllegalStateException failure = new IllegalStateException("synthetic Redis remove failure");
        doThrow(failure).when(projection).remove(ACTOR, TARGET);

        assertSame(failure, assertThrows(IllegalStateException.class,
                () -> service.follow(ACTOR, TARGET, false)));
        verify(repository).delete(ACTOR, TARGET);
        verify(projection).remove(ACTOR, TARGET);
    }
}
