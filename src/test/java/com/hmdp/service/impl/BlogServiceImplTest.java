package com.hmdp.service.impl;

import com.baomidou.mybatisplus.extension.conditions.query.QueryChainWrapper;
import com.hmdp.dto.UserDTO;
import com.hmdp.entity.Blog;
import com.hmdp.entity.Follow;
import com.hmdp.mapper.BlogMapper;
import com.hmdp.service.IFollowService;
import com.hmdp.utils.UserHolder;
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

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
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

        Blog blog = new Blog().setShopId(1L).setTitle("Campus post");
        blogService.saveBlog(blog);

        verify(zSetOperations).add(eq("feed:42"), eq("100"), anyDouble());
        verify(zSetOperations).add(eq("feed:84"), eq("100"), anyDouble());
    }
}
