package io.github.frewily.campushub.service.impl;

import com.baomidou.mybatisplus.extension.conditions.query.QueryChainWrapper;
import io.github.frewily.campushub.dto.UserDTO;
import io.github.frewily.campushub.entity.Blog;
import io.github.frewily.campushub.entity.Follow;
import io.github.frewily.campushub.exception.BusinessException;
import io.github.frewily.campushub.exception.ErrorCode;
import io.github.frewily.campushub.mapper.BlogMapper;
import io.github.frewily.campushub.service.IFollowService;
import io.github.frewily.campushub.utils.UserHolder;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ZSetOperations;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class BlogServiceImplTest {

    @Mock
    private BlogMapper blogMapper;
    @Mock
    private IFollowService followService;
    @Mock
    private QueryChainWrapper<Follow> followQuery;
    @Mock
    private StringRedisTemplate stringRedisTemplate;
    @Mock
    private ZSetOperations<String, String> zSetOperations;

    private BlogServiceImpl blogService;

    @BeforeEach
    void setUp() {
        blogService = new BlogServiceImpl();
        ReflectionTestUtils.setField(blogService, "baseMapper", blogMapper);
        ReflectionTestUtils.setField(blogService, "followService", followService);
        ReflectionTestUtils.setField(blogService, "stringRedisTemplate", stringRedisTemplate);

        UserDTO author = new UserDTO();
        author.setId(7L);
        UserHolder.saveUser(author);
    }

    @AfterEach
    void tearDown() {
        UserHolder.removeUser();
    }

    @Test
    void shouldFanOutPostToEachFollowersOwnFeed() {
        Follow firstFollower = new Follow().setUserId(42L);
        Follow secondFollower = new Follow().setUserId(84L);
        when(blogMapper.insert(any(Blog.class))).thenAnswer(invocation -> {
            Blog blog = invocation.getArgument(0);
            blog.setId(100L);
            return 1;
        });
        when(followService.query()).thenReturn(followQuery);
        when(followQuery.eq("follow_user_id", 7L)).thenReturn(followQuery);
        when(followQuery.list()).thenReturn(Arrays.asList(firstFollower, secondFollower));
        when(stringRedisTemplate.opsForZSet()).thenReturn(zSetOperations);

        Blog blog = new Blog().setShopId(1L).setTitle("Campus post").setUserId(999L);
        blogService.saveBlog(blog);

        verify(zSetOperations).add(eq("feed:42"), eq("100"), anyDouble());
        verify(zSetOperations).add(eq("feed:84"), eq("100"), anyDouble());
        assertEquals(7L, blog.getUserId());
    }

    @Test
    void shouldAcceptNullShopIdAndFanOutPostToEachFollowersOwnFeed() {
        Follow firstFollower = new Follow().setUserId(42L);
        Follow secondFollower = new Follow().setUserId(84L);
        when(blogMapper.insert(any(Blog.class))).thenAnswer(invocation -> {
            Blog saved = invocation.getArgument(0);
            saved.setId(101L);
            return 1;
        });
        when(followService.query()).thenReturn(followQuery);
        when(followQuery.eq("follow_user_id", 7L)).thenReturn(followQuery);
        when(followQuery.list()).thenReturn(Arrays.asList(firstFollower, secondFollower));
        when(stringRedisTemplate.opsForZSet()).thenReturn(zSetOperations);

        Blog blog = new Blog().setShopId(null).setTitle("Campus post").setUserId(999L);
        blogService.saveBlog(blog);

        verify(zSetOperations).add(eq("feed:42"), eq("101"), anyDouble());
        verify(zSetOperations).add(eq("feed:84"), eq("101"), anyDouble());
        assertEquals(7L, blog.getUserId());
    }

    @ParameterizedTest
    @ValueSource(longs = {0L, -1L})
    void shouldRejectNonPositiveShopIdBeforeDatabaseOrRedis(long shopId) {
        Blog blog = new Blog().setShopId(shopId).setTitle("Campus post");

        assertThrows(BusinessException.class, () -> blogService.saveBlog(blog));

        verifyNoInteractions(blogMapper, followService, stringRedisTemplate);
    }

    @Test
    void shouldNotFanOutWhenPersistenceFails() {
        when(blogMapper.insert(any(Blog.class))).thenReturn(0);

        BusinessException exception = assertThrows(BusinessException.class,
                () -> blogService.saveBlog(new Blog().setShopId(null).setTitle("Campus post")));

        assertEquals(ErrorCode.OPERATION_FAILED, exception.getErrorCode());
        verify(blogMapper).insert(any(Blog.class));
        verifyNoInteractions(followService, stringRedisTemplate);
    }
}
