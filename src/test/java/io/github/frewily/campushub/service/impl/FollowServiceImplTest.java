package io.github.frewily.campushub.service.impl;

import io.github.frewily.campushub.dto.UserDTO;
import io.github.frewily.campushub.exception.BusinessException;
import io.github.frewily.campushub.exception.ErrorCode;
import io.github.frewily.campushub.mapper.FollowMapper;
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


import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
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
    private FollowMapper followMapper;

    private FollowServiceImpl followService;

    @BeforeEach
    void setUp() {
        followService = new FollowServiceImpl();
        ReflectionTestUtils.setField(followService, "baseMapper", followMapper);
        ReflectionTestUtils.setField(followService, "stringRedisTemplate", stringRedisTemplate);

        UserDTO currentUser = new UserDTO();
        currentUser.setId(1L);
        UserHolder.saveUser(currentUser);
    }

    @AfterEach
    void tearDown() {
        UserHolder.removeUser();
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
        BusinessException exception = assertThrows(
                BusinessException.class,
                () -> followService.follow(1L, true)
        );

        assertEquals(ErrorCode.CONFLICT, exception.getErrorCode());
        verifyNoInteractions(followMapper, stringRedisTemplate);
    }
}
