package io.github.frewily.campushub.social.application;

import io.github.frewily.campushub.dto.UserDTO;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.*;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class FollowQueryServiceTest {
    private FollowLookupRepository repository;
    private FollowProjectionReader projection;
    private FollowUserReader users;
    private FollowQueryService service;

    @BeforeEach
    void setUp() {
        repository = mock(FollowLookupRepository.class);
        projection = mock(FollowProjectionReader.class);
        users = mock(FollowUserReader.class);
        service = new FollowQueryService(repository, projection, users);
    }

    @Test
    void existenceUsesOnlyTheDatabaseCount() {
        when(repository.count(1L, 9L)).thenReturn(0, 2);
        assertFalse(service.isFollow(1L, 9L));
        assertTrue(service.isFollow(1L, 9L));
        verify(repository, times(2)).count(1L, 9L);
        verifyNoInteractions(projection, users);
    }

    @Test
    void emptyOrNullProjectionStillReadsBothSetsBeforeReturningEmpty() {
        when(projection.following(1L)).thenReturn(null, Collections.emptySet());
        when(projection.following(9L)).thenReturn(Collections.singleton("3"));
        assertTrue(service.common(1L, 9L).isEmpty());
        assertTrue(service.common(1L, 9L).isEmpty());
        verify(projection, times(2)).following(1L);
        verify(projection, times(2)).following(9L);
        verifyNoInteractions(repository, users);
    }

    @Test
    void nullTargetAndDisjointSetsReturnEmptyWithoutQueryingProfiles() {
        when(projection.following(1L)).thenReturn(Collections.singleton("3"));
        when(projection.following(9L)).thenReturn(null, Collections.singleton("4"));
        assertTrue(service.common(1L, 9L).isEmpty());
        assertTrue(service.common(1L, 9L).isEmpty());
        verifyNoInteractions(repository, users);
    }

    @Test
    void intersectionUsesLongIdsAndKeepsProfileReaderOrderWithoutSqlRelationshipFallback() {
        when(projection.following(1L)).thenReturn(new LinkedHashSet<>(Arrays.asList("9007199254740993", "3", "4")));
        when(projection.following(9L)).thenReturn(new LinkedHashSet<>(Arrays.asList("3", "9007199254740993", "8")));
        List<UserDTO> expected = Collections.singletonList(new UserDTO());
        when(users.findByIds(anyList())).thenAnswer(invocation -> {
            assertEquals(new HashSet<>(Arrays.asList(3L, 9007199254740993L)), new HashSet<>(invocation.<List<Long>>getArgument(0)));
            return expected;
        });
        assertSame(expected, service.common(1L, 9L));
        verifyNoInteractions(repository);
    }

    @Test
    void malformedIntersectingMemberIsNotSilentlyDroppedOrRepaired() {
        when(projection.following(1L)).thenReturn(Collections.singleton("not-a-long"));
        when(projection.following(9L)).thenReturn(Collections.singleton("not-a-long"));
        assertThrows(NumberFormatException.class, () -> service.common(1L, 9L));
        verifyNoInteractions(repository, users);
    }

    @Test
    void projectionErrorsPropagateWithoutFallback() {
        RuntimeException failure = new IllegalStateException("synthetic projection error");
        when(projection.following(1L)).thenThrow(failure);
        assertSame(failure, assertThrows(RuntimeException.class, () -> service.common(1L, 9L)));
        verify(projection, never()).following(9L);
        verifyNoInteractions(repository, users);
    }

    @Test
    void databaseAndProfileFailuresAreNotReportedAsSuccess() {
        RuntimeException failure = new IllegalStateException("synthetic SQL error");
        when(repository.count(1L, 9L)).thenThrow(failure);
        assertSame(failure, assertThrows(RuntimeException.class, () -> service.isFollow(1L, 9L)));
        when(projection.following(1L)).thenReturn(Collections.singleton("3"));
        when(projection.following(9L)).thenReturn(Collections.singleton("3"));
        when(users.findByIds(Collections.singletonList(3L))).thenThrow(failure);
        assertSame(failure, assertThrows(RuntimeException.class, () -> service.common(1L, 9L)));
    }

    @Test
    void readingTheCurrentUsersOwnProjectionDoesNotApplyTheWriteSelfFollowRule() {
        when(projection.following(1L)).thenReturn(Collections.singleton("3"));
        when(users.findByIds(Collections.singletonList(3L))).thenReturn(Collections.emptyList());
        assertTrue(service.common(1L, 1L).isEmpty());
        verify(projection, times(2)).following(1L);
        verify(users).findByIds(Collections.singletonList(3L));
    }
}
