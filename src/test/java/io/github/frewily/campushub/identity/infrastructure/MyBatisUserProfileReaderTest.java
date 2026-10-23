package io.github.frewily.campushub.identity.infrastructure;

import io.github.frewily.campushub.dto.UserDTO;
import io.github.frewily.campushub.dto.response.UserInfoResponse;
import io.github.frewily.campushub.entity.User;
import io.github.frewily.campushub.entity.UserInfo;
import io.github.frewily.campushub.mapper.UserInfoMapper;
import io.github.frewily.campushub.mapper.UserMapper;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.time.LocalDateTime;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class MyBatisUserProfileReaderTest {
    @Test
    void userLookupMapsOnlyTheThreeUserDtoFieldsAndPreservesEntity() {
        UserMapper users = mock(UserMapper.class);
        UserInfoMapper infos = mock(UserInfoMapper.class);
        User entity = new User().setId(9007199254740993L).setNickName("synthetic")
                .setIcon("/avatar.png").setPhone("13800000000").setPassword("encoded")
                .setStatus("DISABLED");
        LocalDateTime created = LocalDateTime.of(2020, 1, 2, 3, 4);
        entity.setCreateTime(created).setUpdateTime(created);
        when(users.selectById(9007199254740993L)).thenReturn(entity);

        UserDTO dto = new MyBatisUserProfileReader(users, infos).findUserById(9007199254740993L);

        assertEquals(9007199254740993L, dto.getId());
        assertEquals("synthetic", dto.getNickName());
        assertEquals("/avatar.png", dto.getIcon());
        assertFalse(hasField(dto, "phone"));
        assertFalse(hasField(dto, "password"));
        assertFalse(hasField(dto, "status"));
        assertEquals(created, entity.getCreateTime());
        assertEquals(created, entity.getUpdateTime());
        verify(users).selectById(9007199254740993L);
        verifyNoMoreInteractions(users);
        verifyNoInteractions(infos);
    }

    @Test
    void missingUserReturnsNullWithoutFallbackLookup() {
        UserMapper users = mock(UserMapper.class);
        UserInfoMapper infos = mock(UserInfoMapper.class);
        when(users.selectById(11L)).thenReturn(null);

        assertNull(new MyBatisUserProfileReader(users, infos).findUserById(11L));
        verify(users).selectById(11L);
        verifyNoMoreInteractions(users);
        verifyNoInteractions(infos);
    }

    @Test
    void infoLookupUsesTheLegacyNineFieldResponseAndDoesNotMutateEntityTimestamps() {
        UserMapper users = mock(UserMapper.class);
        UserInfoMapper infos = mock(UserInfoMapper.class);
        LocalDateTime created = LocalDateTime.of(2021, 2, 3, 4, 5);
        UserInfo entity = new UserInfo().setUserId(12L).setCity("Synthetic City")
                .setIntroduce("Synthetic bio").setFans(2).setFollowee(3).setGender(false)
                .setBirthday(LocalDate.of(2001, 2, 3)).setCredits(4).setLevel(true)
                .setCreateTime(created).setUpdateTime(created.plusDays(1));
        when(infos.selectById(12L)).thenReturn(entity);

        UserInfoResponse response = new MyBatisUserProfileReader(users, infos).findInfoById(12L);

        assertEquals(12L, response.getUserId());
        assertEquals("Synthetic City", response.getCity());
        assertEquals("Synthetic bio", response.getIntroduce());
        assertEquals(2, response.getFans());
        assertEquals(3, response.getFollowee());
        assertEquals(Boolean.FALSE, response.getGender());
        assertEquals(LocalDate.of(2001, 2, 3), response.getBirthday());
        assertEquals(4, response.getCredits());
        assertEquals(Boolean.TRUE, response.getLevel());
        assertEquals(9, UserInfoResponse.class.getDeclaredFields().length);
        assertEquals(created, entity.getCreateTime());
        assertEquals(created.plusDays(1), entity.getUpdateTime());
        verify(infos).selectById(12L);
        verifyNoMoreInteractions(infos);
        verifyNoInteractions(users);
    }

    @Test
    void missingInfoReturnsNull() {
        UserMapper users = mock(UserMapper.class);
        UserInfoMapper infos = mock(UserInfoMapper.class);
        when(infos.selectById(13L)).thenReturn(null);

        assertNull(new MyBatisUserProfileReader(users, infos).findInfoById(13L));
        verify(infos).selectById(13L);
        verifyNoMoreInteractions(infos);
        verifyNoInteractions(users);
    }

    private static boolean hasField(Object target, String name) {
        try {
            target.getClass().getDeclaredField(name);
            return true;
        } catch (NoSuchFieldException ignored) {
            return false;
        }
    }
}
