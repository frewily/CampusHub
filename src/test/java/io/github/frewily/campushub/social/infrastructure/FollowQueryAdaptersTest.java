package io.github.frewily.campushub.social.infrastructure;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.frewily.campushub.dto.UserDTO;
import io.github.frewily.campushub.entity.User;
import io.github.frewily.campushub.mapper.FollowMapper;
import io.github.frewily.campushub.service.IUserService;
import io.github.frewily.campushub.social.application.FollowQueryService;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ZSetOperations;

import java.util.*;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class FollowQueryAdaptersTest {
    /** Moved from the old FollowServiceImplTest with its canonical ZSet regression assertions retained. */
    @Test
    @SuppressWarnings("unchecked")
    void shouldIntersectTheSameSortedSetStructureUsedForFollowing() {
        StringRedisTemplate redis = mock(StringRedisTemplate.class);
        ZSetOperations<String, String> zsets = mock(ZSetOperations.class);
        IUserService users = mock(IUserService.class);
        FollowMapper mapper = mock(FollowMapper.class);
        when(redis.opsForZSet()).thenReturn(zsets);
        when(zsets.range("follows:1", 0, -1)).thenReturn(new LinkedHashSet<>(Arrays.asList("2", "3")));
        when(zsets.range("follows:9", 0, -1)).thenReturn(new LinkedHashSet<>(Arrays.asList("3", "4")));
        when(users.listByIds(Arrays.asList(3L))).thenReturn(Arrays.asList(new User().setId(3L).setNickName("common")));
        FollowQueryService service = new FollowQueryService(new MyBatisFollowLookupRepository(mapper),
                new RedisFollowProjectionReader(redis), new LegacyFollowUserReader(users));
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
    void profileBridgeRetainsOnlyUserDtoFieldsWithoutStatusFiltering() {
        IUserService users = mock(IUserService.class);
        when(users.listByIds(Arrays.asList(3L))).thenReturn(Arrays.asList(new User().setId(3L).setNickName("common")
                .setIcon("icon.png").setPhone("00000000003").setPassword("synthetic-never-real").setStatus("DISABLED")));
        UserDTO result = new LegacyFollowUserReader(users).findByIds(Arrays.asList(3L)).get(0);
        Set<String> fields = new HashSet<>(); new ObjectMapper().valueToTree(result).fieldNames().forEachRemaining(fields::add);
        assertEquals(new HashSet<>(Arrays.asList("id", "nickName", "icon")), fields);
        assertEquals(3L, result.getId());
        assertEquals("common", result.getNickName());
        assertEquals("icon.png", result.getIcon());
        verify(users).listByIds(Arrays.asList(3L));
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
}
