package io.github.frewily.campushub.service.impl;

import com.baomidou.mybatisplus.extension.conditions.query.QueryChainWrapper;
import io.github.frewily.campushub.dto.Result;
import io.github.frewily.campushub.dto.ScrollResult;
import io.github.frewily.campushub.dto.UserDTO;
import io.github.frewily.campushub.entity.Blog;
import io.github.frewily.campushub.exception.BusinessException;
import io.github.frewily.campushub.exception.ErrorCode;
import io.github.frewily.campushub.service.IUserService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.DefaultTypedTuple;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ZSetOperations;
import org.springframework.test.util.ReflectionTestUtils;
import io.github.frewily.campushub.utils.UserHolder;

import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** Cursor calculation uses Redis rows, not the possibly empty visible database list. */
class BlogFeedPaginationServiceTest {
    private BlogServiceImpl service;
    private StringRedisTemplate redis;
    private ZSetOperations<String, String> zset;
    private QueryChainWrapper<Blog> query;
    private IUserService users;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setup() {
        service = spy(new BlogServiceImpl());
        redis = mock(StringRedisTemplate.class);
        zset = mock(ZSetOperations.class);
        query = mock(QueryChainWrapper.class);
        users = mock(IUserService.class);
        ReflectionTestUtils.setField(service, "stringRedisTemplate", redis);
        ReflectionTestUtils.setField(service, "userService", users);
        doReturn(query).when(service).query();
        when(redis.opsForZSet()).thenReturn(zset);
        UserDTO identity = new UserDTO();
        identity.setId(7L);
        UserHolder.saveUser(identity);
    }

    @AfterEach
    void cleanup() { UserHolder.removeUser(); }

    private void filteredRows() {
        when(query.in(eq("id"), anyCollection())).thenReturn(query);
        when(query.last(anyString())).thenReturn(query);
        when(query.list()).thenReturn(Collections.emptyList());
    }

    private Set<ZSetOperations.TypedTuple<String>> rows(String firstId, double firstScore,
                                                        String secondId, double secondScore) {
        return new LinkedHashSet<>(Arrays.asList(new DefaultTypedTuple<>(firstId, firstScore),
                new DefaultTypedTuple<>(secondId, secondScore)));
    }

    private ScrollResult page(long max, int offset) {
        Result result = service.queryBlogOfFollow(max, offset);
        assertTrue(result.getSuccess());
        return (ScrollResult) result.getData();
    }

    @Test
    void continuesSameScoreUsingCumulativeOffsetEvenWhenBothDatabaseRowsAreFilteredOut() {
        when(zset.reverseRangeByScoreWithScores("feed:7", 0, 1000, 2, 2))
                .thenReturn(rows("105", 1000, "104", 1000));
        filteredRows();
        ScrollResult page = page(1000, 2);
        assertEquals(1000L, page.getMinTime());
        assertEquals(4, page.getOffset());
        assertTrue(page.getList().isEmpty());
        verify(query).in("id", Arrays.asList(105L, 104L));
        verify(query).last("ORDER BY FIELD(id,105,104)");
        verifyNoInteractions(users);
        verify(zset).reverseRangeByScoreWithScores("feed:7", 0, 1000, 2, 2);
        verifyNoMoreInteractions(zset);
    }

    @Test
    void firstPageAtSameUpperBoundStartsOffsetAtItsRawCount() {
        when(zset.reverseRangeByScoreWithScores("feed:7", 0, 1000, 0, 2))
                .thenReturn(rows("107", 1000, "106", 1000));
        filteredRows();
        ScrollResult page = page(1000, 0);
        assertEquals(1000L, page.getMinTime());
        assertEquals(2, page.getOffset());
    }

    @Test
    void lowerEndingScoreResetsOffsetInsteadOfCarryingPreviousScoreSkipCount() {
        when(zset.reverseRangeByScoreWithScores("feed:7", 0, 2000, 5, 2))
                .thenReturn(rows("103", 1000, "102", 1000));
        filteredRows();
        ScrollResult page = page(2000, 5);
        assertEquals(1000L, page.getMinTime());
        assertEquals(2, page.getOffset());
    }

    @Test
    void mixedScorePageCountsOnlyItsEndingScoreGroup() {
        when(zset.reverseRangeByScoreWithScores("feed:7", 0, 2000, 4, 2))
                .thenReturn(rows("202", 2000, "103", 1000));
        filteredRows();
        ScrollResult page = page(2000, 4);
        assertEquals(1000L, page.getMinTime());
        assertEquals(1, page.getOffset());
    }

    @Test
    void lastSingleMemberAtSameScoreKeepsAlreadyConsumedOffsetAndLosslessLongId() {
        String id = "9007199254740997";
        when(zset.reverseRangeByScoreWithScores("feed:7", 0, 1000, 6, 2))
                .thenReturn(new LinkedHashSet<>(Collections.singletonList(new DefaultTypedTuple<>(id, 1000D))));
        filteredRows();
        ScrollResult page = page(1000, 6);
        assertEquals(7, page.getOffset());
        assertEquals(1000L, page.getMinTime());
        verify(query).in("id", Collections.singletonList(9007199254740997L));
        verify(query).last("ORDER BY FIELD(id," + id + ")");
    }

    @Test
    void nullOrEmptyRedisBatchPreservesTerminalEmptySuccessWithoutDatabaseOrProfileRead() {
        when(zset.reverseRangeByScoreWithScores("feed:7", 0, 1000, 7, 2))
                .thenReturn(null, Collections.emptySet());
        for (int i = 0; i < 2; i++) {
            Result result = service.queryBlogOfFollow(1000L, 7);
            assertTrue(result.getSuccess());
            assertNull(result.getData());
        }
        verifyNoInteractions(query, users);
    }

    @Test
    void cumulativeOffsetMayReachButMustNotExceedIntegerRange() {
        when(zset.reverseRangeByScoreWithScores("feed:7", 0, 1000, Integer.MAX_VALUE - 1L, 2))
                .thenReturn(new LinkedHashSet<>(Collections.singletonList(new DefaultTypedTuple<>("101", 1000D))));
        filteredRows();
        assertEquals(Integer.MAX_VALUE, page(1000, Integer.MAX_VALUE - 1).getOffset());
    }

    @Test
    void unrepresentableNextOffsetFailsBeforeDatabaseReadRatherThanWrappingOrRepeating() {
        when(zset.reverseRangeByScoreWithScores("feed:7", 0, 1000, Integer.MAX_VALUE, 2))
                .thenReturn(new LinkedHashSet<>(Collections.singletonList(new DefaultTypedTuple<>("101", 1000D))));
        BusinessException failure = assertThrows(BusinessException.class,
                () -> service.queryBlogOfFollow(1000L, Integer.MAX_VALUE));
        assertEquals(ErrorCode.OPERATION_FAILED, failure.getErrorCode());
        assertNull(failure.getCause());
        verifyNoInteractions(query, users);
        verify(zset).reverseRangeByScoreWithScores("feed:7", 0, 1000, Integer.MAX_VALUE, 2);
        verifyNoMoreInteractions(zset);
    }
}
