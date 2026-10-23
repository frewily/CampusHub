package io.github.frewily.campushub.social.infrastructure;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.frewily.campushub.entity.Follow;
import io.github.frewily.campushub.dto.UserDTO;
import io.github.frewily.campushub.entity.User;
import io.github.frewily.campushub.mapper.FollowMapper;
import io.github.frewily.campushub.mapper.UserMapper;
import io.github.frewily.campushub.social.application.FollowCommandService;
import io.github.frewily.campushub.social.application.FollowQueryService;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ZSetOperations;

import java.util.*;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class FollowQueryAdaptersTest {
    /** Retains the canonical ZSet regression assertions from the legacy follow adapter coverage. */
    @Test
    @SuppressWarnings("unchecked")
    void shouldIntersectTheSameSortedSetStructureUsedForFollowing() {
        StringRedisTemplate redis = mock(StringRedisTemplate.class);
        ZSetOperations<String, String> zsets = mock(ZSetOperations.class);
        UserMapper users = mock(UserMapper.class);
        FollowMapper mapper = mock(FollowMapper.class);
        when(redis.opsForZSet()).thenReturn(zsets);
        when(zsets.range("follows:1", 0, -1)).thenReturn(new LinkedHashSet<>(Arrays.asList("2", "3")));
        when(zsets.range("follows:9", 0, -1)).thenReturn(new LinkedHashSet<>(Arrays.asList("3", "4")));
        when(users.selectBatchIds(Arrays.asList(3L))).thenReturn(Arrays.asList(new User().setId(3L).setNickName("common")));
        FollowQueryService service = new FollowQueryService(new MyBatisFollowLookupRepository(mapper),
                new RedisFollowProjectionReader(redis), new MyBatisFollowUserReader(users));
        List<UserDTO> result = service.common(1L, 9L);
        assertNotNull(result, "common follows must come from the canonical ZSet data");
        assertEquals(1, result.size());
        assertEquals(3L, result.get(0).getId());
        verify(redis, never()).opsForSet();
        verify(zsets).range("follows:1", 0, -1);
        verify(zsets).range("follows:9", 0, -1);
        verifyNoMoreInteractions(zsets);
        verifyNoInteractions(mapper);
    }

    @Test
    @SuppressWarnings({"rawtypes", "unchecked"})
    void countBindsBothActorAndTargetAndDoesNotWrite() {
        FollowMapper mapper = mock(FollowMapper.class);
        when(mapper.selectCount(any())).thenReturn(1);
        assertEquals(1, new MyBatisFollowLookupRepository(mapper).count(9007199254740993L, 9L));
        ArgumentCaptor<QueryWrapper> query = ArgumentCaptor.forClass(QueryWrapper.class);
        verify(mapper).selectCount(query.capture());
        String predicate = query.getValue().getSqlSegment();
        assertEquals("(user_id = #{ew.paramNameValuePairs.MPGENVAL1} AND follow_user_id = #{ew.paramNameValuePairs.MPGENVAL2})", predicate.trim());
        assertEquals(9007199254740993L, query.getValue().getParamNameValuePairs().get("MPGENVAL1"));
        assertEquals(9L, query.getValue().getParamNameValuePairs().get("MPGENVAL2"));
        verifyNoMoreInteractions(mapper);
    }

    @Test
    void profileReaderRetainsOnlyUserDtoFieldsWithoutStatusFilteringOrReordering() {
        UserMapper users = mock(UserMapper.class);
        long largeId = 9007199254740993L;
        List<Long> ids = Arrays.asList(3L, largeId, 4L);
        when(users.selectBatchIds(ids)).thenReturn(Arrays.asList(
                new User().setId(largeId).setNickName("large").setIcon("large.svg").setPhone("synthetic-phone")
                        .setPassword("synthetic-never-real").setStatus("DISABLED"),
                new User().setId(3L).setNickName("common").setIcon("icon.png").setStatus("ACTIVE")));
        List<UserDTO> results = new MyBatisFollowUserReader(users).findByIds(ids);
        assertEquals(2, results.size());
        assertEquals(Arrays.asList(largeId, 3L), Arrays.asList(results.get(0).getId(), results.get(1).getId()),
                "adapter retains mapper result order and naturally omits missing rows");
        UserDTO result = results.get(0);
        Set<String> fields = new HashSet<>(); new ObjectMapper().valueToTree(result).fieldNames().forEachRemaining(fields::add);
        assertEquals(new HashSet<>(Arrays.asList("id", "nickName", "icon")), fields);
        assertEquals(largeId, result.getId());
        assertEquals("large", result.getNickName());
        assertEquals("large.svg", result.getIcon());
        assertEquals("common", results.get(1).getNickName());
        assertEquals("icon.png", results.get(1).getIcon());
        verify(users).selectBatchIds(ids);
        verifyNoMoreInteractions(users);
    }

    @Test
    void profileReaderPropagatesMapperFailuresAndOnlyReadsUsers() {
        UserMapper users = mock(UserMapper.class);
        List<Long> ids = Arrays.asList(41L, 42L);
        IllegalStateException failure = new IllegalStateException("synthetic SQL failure");
        when(users.selectBatchIds(ids)).thenThrow(failure);

        assertSame(failure, assertThrows(IllegalStateException.class,
                () -> new MyBatisFollowUserReader(users).findByIds(ids)));
        verify(users).selectBatchIds(ids);
        verifyNoMoreInteractions(users);
    }

    @Test
    @SuppressWarnings("unchecked")
    void zsetReadFailureIsNotConvertedToAnEmptyResult() {
        StringRedisTemplate redis = mock(StringRedisTemplate.class);
        ZSetOperations<String, String> zsets = mock(ZSetOperations.class);
        when(redis.opsForZSet()).thenReturn(zsets);
        RuntimeException failure = new IllegalStateException("synthetic wrong type");
        when(zsets.range("follows:1", 0, -1)).thenThrow(failure);
        assertSame(failure, assertThrows(RuntimeException.class, () -> new RedisFollowProjectionReader(redis).following(1L)));
        verify(zsets).range("follows:1", 0, -1);
        verifyNoMoreInteractions(zsets);
    }

    @Test
    void commandRepositoryBindsActorAndTargetForExistsAndDelete() {
        FollowMapper mapper = mock(FollowMapper.class);
        when(mapper.selectCount(any())).thenReturn(1);
        when(mapper.delete(any(QueryWrapper.class))).thenReturn(1);
        MyBatisFollowCommandRepository repository = new MyBatisFollowCommandRepository(mapper);

        assertTrue(repository.exists(9007199254740993L, 9L));
        assertTrue(repository.delete(9007199254740993L, 9L));

        ArgumentCaptor<QueryWrapper> queries = ArgumentCaptor.forClass(QueryWrapper.class);
        verify(mapper).selectCount(queries.capture());
        assertCommandRelationship(queries.getValue(), 9007199254740993L, 9L);
        verify(mapper).delete(queries.capture());
        assertCommandRelationship(queries.getValue(), 9007199254740993L, 9L);
        verifyNoMoreInteractions(mapper);
    }

    @Test
    void commandRepositoryCreatesRelationshipAndRecoversDuplicateUniqueKey() {
        FollowMapper mapper = mock(FollowMapper.class);
        when(mapper.insert(any(Follow.class))).thenReturn(1);
        MyBatisFollowCommandRepository repository = new MyBatisFollowCommandRepository(mapper);

        assertTrue(repository.create(41L, 42L));
        ArgumentCaptor<Follow> created = ArgumentCaptor.forClass(Follow.class);
        verify(mapper).insert(created.capture());
        assertEquals(41L, created.getValue().getUserId());
        assertEquals(42L, created.getValue().getFollowUserId());

        reset(mapper);
        when(mapper.insert(any(Follow.class))).thenThrow(new DuplicateKeyException("synthetic duplicate"));
        assertTrue(repository.create(41L, 42L), "a concurrent insert of the unique relationship is successful recovery");
        verify(mapper).insert(any(Follow.class));
        verifyNoMoreInteractions(mapper);
    }

    @Test
    void commandRepositoryPropagatesNonDuplicateSqlFailures() {
        FollowMapper mapper = mock(FollowMapper.class);
        IllegalStateException failure = new IllegalStateException("synthetic SQL failure");
        when(mapper.insert(any(Follow.class))).thenThrow(failure);

        assertSame(failure, assertThrows(IllegalStateException.class,
                () -> new MyBatisFollowCommandRepository(mapper).create(41L, 42L)));
    }

    @Test
    void zeroAffectedRowsAreNotReportedAsSuccessfulWrites() {
        FollowMapper mapper = mock(FollowMapper.class);
        when(mapper.selectCount(any())).thenReturn(0);
        MyBatisFollowCommandRepository repository = new MyBatisFollowCommandRepository(mapper);
        assertFalse(repository.exists(41L, 42L));
        assertFalse(repository.create(41L, 42L));
        assertFalse(repository.delete(41L, 42L));
    }

    @Test
    @SuppressWarnings("unchecked")
    void projectionWriterUsesCanonicalZsetKeyMemberAndExactScore() {
        StringRedisTemplate redis = mock(StringRedisTemplate.class);
        ZSetOperations<String, String> zsets = mock(ZSetOperations.class);
        when(redis.opsForZSet()).thenReturn(zsets);
        RedisFollowProjectionWriter writer = new RedisFollowProjectionWriter(redis);

        writer.add(9007199254740993L, 9007199254740994L, 1_792_598_400_123L);
        writer.remove(9007199254740993L, 9007199254740994L);

        verify(zsets).add("follows:9007199254740993", "9007199254740994", 1_792_598_400_123D);
        verify(zsets).remove("follows:9007199254740993", "9007199254740994");
        verifyNoMoreInteractions(zsets);
    }

    @Test
    @SuppressWarnings("unchecked")
    void projectionWriterPropagatesRedisFailures() {
        StringRedisTemplate redis = mock(StringRedisTemplate.class);
        ZSetOperations<String, String> zsets = mock(ZSetOperations.class);
        when(redis.opsForZSet()).thenReturn(zsets);
        IllegalStateException failure = new IllegalStateException("synthetic Redis failure");
        doThrow(failure).when(zsets).add("follows:41", "42", 100D);

        assertSame(failure, assertThrows(IllegalStateException.class,
                () -> new RedisFollowProjectionWriter(redis).add(41L, 42L, 100L)));
    }

    @Test
    @SuppressWarnings("unchecked")
    void repeatedFollowAdapterRegressionSkipsMapperInsertAndAddsCanonicalRedisProjection() {
        FollowMapper mapper = mock(FollowMapper.class);
        StringRedisTemplate redis = mock(StringRedisTemplate.class);
        ZSetOperations<String, String> zsets = mock(ZSetOperations.class);
        when(mapper.selectCount(any())).thenReturn(1);
        when(redis.opsForZSet()).thenReturn(zsets);
        long score = 1_792_598_400_123L;
        FollowCommandService service = new FollowCommandService(new MyBatisFollowCommandRepository(mapper),
                new RedisFollowProjectionWriter(redis), Clock.fixed(Instant.ofEpochMilli(score), ZoneOffset.UTC));

        service.follow(1L, 9L, true);

        verify(mapper).selectCount(any());
        verify(mapper, never()).insert(any(Follow.class));
        verifyNoMoreInteractions(mapper);
        verify(zsets).add("follows:1", "9", (double) score);
        verifyNoMoreInteractions(zsets);
    }

    @SuppressWarnings("rawtypes")
    private void assertCommandRelationship(QueryWrapper query, long actorId, long targetId) {
        assertEquals("(user_id = #{ew.paramNameValuePairs.MPGENVAL1} AND follow_user_id = #{ew.paramNameValuePairs.MPGENVAL2})",
                query.getSqlSegment().trim());
        assertEquals(actorId, query.getParamNameValuePairs().get("MPGENVAL1"));
        assertEquals(targetId, query.getParamNameValuePairs().get("MPGENVAL2"));
    }
}
