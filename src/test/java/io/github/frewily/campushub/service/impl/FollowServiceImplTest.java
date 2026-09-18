package io.github.frewily.campushub.service.impl;

import io.github.frewily.campushub.dto.Result;
import io.github.frewily.campushub.dto.UserDTO;
import io.github.frewily.campushub.entity.User;
import io.github.frewily.campushub.mapper.FollowMapper;
import io.github.frewily.campushub.service.IUserService;
import io.github.frewily.campushub.utils.UserHolder;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ZSetOperations;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.ArgumentMatchers.eq;

@ExtendWith(MockitoExtension.class)
class FollowServiceImplTest {

    @Mock
    private StringRedisTemplate stringRedisTemplate;
    @Mock
    private ZSetOperations<String, String> zSetOperations;
    @Mock
    private IUserService userService;
    @Mock
    private FollowMapper followMapper;

    private FollowServiceImpl followService;

    @BeforeEach
    void setUp() {
        followService = new FollowServiceImpl();
        ReflectionTestUtils.setField(followService, "baseMapper", followMapper);
        ReflectionTestUtils.setField(followService, "stringRedisTemplate", stringRedisTemplate);
        ReflectionTestUtils.setField(followService, "userService", userService);

        UserDTO currentUser = new UserDTO();
        currentUser.setId(1L);
        UserHolder.saveUser(currentUser);
    }

    @AfterEach
    void tearDown() {
        UserHolder.removeUser();
    }

    @SuppressWarnings("unchecked")
    @Test
    void shouldIntersectTheSameSortedSetStructureUsedForFollowing() {
        when(stringRedisTemplate.opsForZSet()).thenReturn(zSetOperations);
        when(zSetOperations.range("follows:1", 0, -1))
                .thenReturn(new LinkedHashSet<>(Arrays.asList("2", "3")));
        when(zSetOperations.range("follows:9", 0, -1))
                .thenReturn(new LinkedHashSet<>(Arrays.asList("3", "4")));
        when(userService.listByIds(Arrays.asList(3L)))
                .thenReturn(Arrays.asList(new User().setId(3L).setNickName("common")));

        Result result = followService.common(9L);

        assertNotNull(result.getData(), "common follows must come from the canonical ZSet data");
        List<UserDTO> users = (List<UserDTO>) result.getData();
        assertEquals(1, users.size());
        assertEquals(3L, users.get(0).getId());
        verify(stringRedisTemplate, never()).opsForSet();
    }

    @Test
    void shouldTreatRepeatedFollowRequestAsIdempotentAndRepairRedisProjection() {
        when(followMapper.selectCount(any())).thenReturn(1);
        when(stringRedisTemplate.opsForZSet()).thenReturn(zSetOperations);

        followService.follow(9L, true);

        verify(followMapper, never()).insert(any());
        verify(zSetOperations).add(eq("follows:1"), eq("9"), anyDouble());
    }

    @Test
    void shouldRejectFollowingTheCurrentUser() {
        Result result = followService.follow(1L, true);

        assertFalse(result.getSuccess());
        verifyNoInteractions(followMapper, stringRedisTemplate);
    }
}
