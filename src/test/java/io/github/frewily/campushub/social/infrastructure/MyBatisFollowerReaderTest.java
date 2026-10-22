package io.github.frewily.campushub.social.infrastructure;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import io.github.frewily.campushub.entity.Follow;
import io.github.frewily.campushub.mapper.FollowMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class MyBatisFollowerReaderTest {
    private FollowMapper mapper;
    private MyBatisFollowerReader reader;

    @BeforeEach
    void setUp() {
        mapper = mock(FollowMapper.class);
        reader = new MyBatisFollowerReader(mapper);
    }

    @Test
    void bindsOnlyTheAuthorAndLeavesProjectionOrderingAndPaginationUnspecified() {
        Follow row = new Follow().setUserId(42L).setFollowUserId(700L);
        when(mapper.selectList(any())).thenReturn(Collections.singletonList(row));

        assertEquals(Collections.singletonList(42L), reader.findFollowerIds(700L));

        ArgumentCaptor<QueryWrapper<Follow>> captor = ArgumentCaptor.forClass(QueryWrapper.class);
        verify(mapper).selectList(captor.capture());
        verifyNoMoreInteractions(mapper);
        QueryWrapper<Follow> query = captor.getValue();
        // MyBatis renders lazy parameter bindings when it renders the SQL segment.
        String sql = query.getSqlSegment();
        assertEquals("(follow_user_id = #{ew.paramNameValuePairs.MPGENVAL1})", sql.trim());
        assertEquals(Collections.singletonList(700L),
                new java.util.ArrayList<>(query.getParamNameValuePairs().values()));
        assertTrue(query.getSqlSegment().contains("follow_user_id"));
        assertFalse(query.getSqlSegment().toUpperCase().contains("ORDER BY"));
        assertFalse(query.getSqlSegment().toUpperCase().contains("LIMIT"));
        assertNull(query.getSqlSelect());
    }

    @Test
    void preservesMyBatisRowsIncludingDuplicatesNullIdsAndTheirOriginalOrder() {
        when(mapper.selectList(any())).thenReturn(Arrays.asList(
                new Follow().setUserId(9L),
                new Follow().setUserId(null),
                new Follow().setUserId(9L),
                new Follow().setUserId(3L)));

        assertEquals(Arrays.asList(9L, null, 9L, 3L), reader.findFollowerIds(700L));
        verify(mapper).selectList(any());
        verifyNoMoreInteractions(mapper);
    }

    @Test
    void returnsEmptyListWhenNoRelationshipsExist() {
        when(mapper.selectList(any())).thenReturn(Collections.emptyList());

        List<Long> result = reader.findFollowerIds(700L);

        assertTrue(result.isEmpty());
        verify(mapper).selectList(any());
        verifyNoMoreInteractions(mapper);
    }

    @Test
    void propagatesSqlFailureWithoutWrappingIt() {
        RuntimeException failure = new RuntimeException("synthetic SQL failure");
        when(mapper.selectList(any())).thenThrow(failure);

        assertSame(failure, assertThrows(RuntimeException.class, () -> reader.findFollowerIds(700L)));
        verify(mapper).selectList(any());
        verifyNoMoreInteractions(mapper);
    }
}
