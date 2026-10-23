package io.github.frewily.campushub.content.infrastructure;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import io.github.frewily.campushub.dto.UserDTO;
import io.github.frewily.campushub.entity.User;
import io.github.frewily.campushub.mapper.UserMapper;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.LocalDateTime;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class MyBatisBlogUserReaderTest {
    private static final long LARGE_ID = 9007199254740993L;

    @Test
    void findAuthorUsesSelectByIdMapsDisplayFieldsAndPreservesDisabledEntity() {
        UserMapper users = mock(UserMapper.class);
        LocalDateTime created = LocalDateTime.of(2020, 1, 2, 3, 4);
        LocalDateTime updated = created.plusDays(1);
        User entity = new User().setId(LARGE_ID).setNickName("synthetic").setIcon("avatar.svg")
                .setPhone("synthetic-phone").setPassword("synthetic-password").setStatus("DISABLED")
                .setCreateTime(created).setUpdateTime(updated);
        when(users.selectById(LARGE_ID)).thenReturn(entity);

        UserDTO dto = new MyBatisBlogUserReader(users).findAuthor(LARGE_ID);

        assertEquals(LARGE_ID, dto.getId());
        assertEquals("synthetic", dto.getNickName());
        assertEquals("avatar.svg", dto.getIcon());
        assertEquals(3, UserDTO.class.getDeclaredFields().length);
        assertEquals("DISABLED", entity.getStatus());
        assertEquals(created, entity.getCreateTime());
        assertEquals(updated, entity.getUpdateTime());
        verify(users).selectById(LARGE_ID);
        verifyNoMoreInteractions(users);
    }

    @Test
    void findAuthorReturnsNullForMissingRow() {
        UserMapper users = mock(UserMapper.class);
        when(users.selectById(73L)).thenReturn(null);

        assertNull(new MyBatisBlogUserReader(users).findAuthor(73L));

        verify(users).selectById(73L);
        verifyNoMoreInteractions(users);
    }

    @Test
    void findLikersBindsTypedLongIdsUsesLegacyFieldOrderAndMapsRowsWithoutMutation() {
        UserMapper users = mock(UserMapper.class);
        List<Long> ids = Arrays.asList(LARGE_ID, 41L, 9007199254740995L);
        LocalDateTime created = LocalDateTime.of(2021, 5, 6, 7, 8);
        User first = user(LARGE_ID, "first", "DISABLED", created);
        User second = user(41L, "second", "ACTIVE", created.plusDays(1));
        when(users.selectList(any())).thenReturn(Arrays.asList(first, second));

        List<UserDTO> result = new MyBatisBlogUserReader(users).findLikers(ids);

        assertEquals(Arrays.asList(LARGE_ID, 41L), Arrays.asList(result.get(0).getId(), result.get(1).getId()));
        assertEquals(Arrays.asList("first", "second"), Arrays.asList(result.get(0).getNickName(), result.get(1).getNickName()));
        assertEquals(created, first.getCreateTime());
        assertEquals(created.plusDays(1), second.getCreateTime());
        ArgumentCaptor<QueryWrapper<User>> captor = wrapperCaptor();
        verify(users).selectList(captor.capture());
        QueryWrapper<User> wrapper = captor.getValue();
        assertTrue(wrapper.getSqlSegment().contains("id IN"));
        assertTrue(wrapper.getSqlSegment().contains("ORDER BY FIELD(id,9007199254740993,41,9007199254740995)"));
        assertTrue(wrapper.getParamNameValuePairs().values().containsAll(ids));
        assertTrue(wrapper.getParamNameValuePairs().values().stream().allMatch(Long.class::isInstance));
        verifyNoMoreInteractions(users);
    }

    @Test
    void findLikersPreservesMissingRowsAndEmptyDatabaseResult() {
        UserMapper users = mock(UserMapper.class);
        when(users.selectList(any())).thenReturn(Collections.emptyList());

        assertTrue(new MyBatisBlogUserReader(users).findLikers(Arrays.asList(1L, 2L)).isEmpty());

        verify(users).selectList(any());
        verifyNoMoreInteractions(users);
    }

    @Test
    void sqlFailuresPropagateAndReaderPerformsNoWrites() {
        UserMapper users = mock(UserMapper.class);
        RuntimeException failure = new RuntimeException("synthetic SQL failure");
        when(users.selectById(1L)).thenThrow(failure);
        when(users.selectList(any())).thenThrow(failure);

        assertSame(failure, assertThrows(RuntimeException.class, () -> new MyBatisBlogUserReader(users).findAuthor(1L)));
        assertSame(failure, assertThrows(RuntimeException.class, () -> new MyBatisBlogUserReader(users).findLikers(Collections.singletonList(1L))));

        verify(users).selectById(1L);
        verify(users, times(1)).selectList(any());
        verifyNoMoreInteractions(users);
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private static ArgumentCaptor<QueryWrapper<User>> wrapperCaptor() {
        return (ArgumentCaptor) ArgumentCaptor.forClass(QueryWrapper.class);
    }

    private static User user(long id, String name, String status, LocalDateTime created) {
        return new User().setId(id).setNickName(name).setIcon(name + ".svg")
                .setPhone("synthetic").setPassword("synthetic").setStatus(status)
                .setCreateTime(created).setUpdateTime(created.plusHours(1));
    }
}
