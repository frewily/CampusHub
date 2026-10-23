package io.github.frewily.campushub.service.impl;

import io.github.frewily.campushub.content.application.BlogUserReader;
import io.github.frewily.campushub.dto.Result;
import io.github.frewily.campushub.dto.UserDTO;
import io.github.frewily.campushub.entity.Blog;
import io.github.frewily.campushub.exception.BusinessException;
import io.github.frewily.campushub.exception.ErrorCode;
import io.github.frewily.campushub.utils.UserHolder;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ZSetOperations;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class BlogUserEnrichmentServiceTest {
    private BlogServiceImpl service;
    private BlogUserReader users;
    private StringRedisTemplate redis;
    private ZSetOperations<String, String> zsets;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setup() {
        service = spy(new BlogServiceImpl());
        users = mock(BlogUserReader.class);
        redis = mock(StringRedisTemplate.class);
        zsets = mock(ZSetOperations.class);
        ReflectionTestUtils.setField(service, "blogUserReader", users);
        ReflectionTestUtils.setField(service, "stringRedisTemplate", redis);
        UserHolder.removeUser();
    }

    @AfterEach void cleanup() { UserHolder.removeUser(); }

    @Test
    void detailsUseDatabaseAuthorDisplayRatherThanSessionIdentity() {
        Blog blog = new Blog().setId(81L).setUserId(9007199254745993L);
        UserDTO author = new UserDTO(); author.setId(blog.getUserId());
        author.setNickName("database-name"); author.setIcon("database.svg");
        doReturn(blog).when(service).getById(81L);
        when(users.findAuthor(blog.getUserId())).thenReturn(author);
        Result result = service.queryBlogById(81L);
        assertSame(blog, result.getData());
        assertEquals("database-name", blog.getName()); assertEquals("database.svg", blog.getIcon());
        assertFalse(blog.getIsLike());
        verify(users).findAuthor(blog.getUserId()); verifyNoMoreInteractions(users);
        verifyNoInteractions(redis);
    }

    @Test
    void absentPostStillFailsBeforeAnyProfileOrRedisRead() {
        doReturn(null).when(service).getById(82L);
        assertEquals(ErrorCode.NOT_FOUND, assertThrows(BusinessException.class,
                () -> service.queryBlogById(82L)).getErrorCode());
        assertEquals(ErrorCode.NOT_FOUND, assertThrows(BusinessException.class,
                () -> service.queryBlogLikes(82L)).getErrorCode());
        verifyNoInteractions(users, redis);
    }

    @Test
    void missingAuthorStillFailsInsteadOfInventingDisplayData() {
        doReturn(new Blog().setId(83L).setUserId(99L)).when(service).getById(83L);
        assertThrows(NullPointerException.class, () -> service.queryBlogById(83L));
        verify(users).findAuthor(99L); verifyNoMoreInteractions(users);
        verifyNoInteractions(redis);
    }

    @Test
    void topFiveAreParsedInRedisOrderAndPortResultIsNotRefilledOrReordered() {
        doReturn(new Blog().setId(84L)).when(service).getById(84L);
        when(redis.opsForZSet()).thenReturn(zsets);
        when(zsets.range("blog:liked:84", 0, 4)).thenReturn(new LinkedHashSet<>(
                Arrays.asList("9007199254745997", "3", "9007199254745995")));
        List<Long> ids = Arrays.asList(9007199254745997L, 3L, 9007199254745995L);
        UserDTO user = new UserDTO(); user.setId(9007199254745995L);
        List<UserDTO> found = Collections.singletonList(user);
        when(users.findLikers(ids)).thenReturn(found);
        assertSame(found, service.queryBlogLikes(84L).getData());
        verify(users).findLikers(ids); verifyNoMoreInteractions(users);
        verify(zsets).range("blog:liked:84", 0, 4); verifyNoMoreInteractions(zsets);
    }

    @Test
    void nullAndEmptyLikeProjectionSkipProfileReads() {
        doReturn(new Blog().setId(85L)).when(service).getById(85L);
        when(redis.opsForZSet()).thenReturn(zsets);
        when(zsets.range("blog:liked:85", 0, 4)).thenReturn(null, Collections.emptySet());
        assertEquals(Collections.emptyList(), service.queryBlogLikes(85L).getData());
        assertEquals(Collections.emptyList(), service.queryBlogLikes(85L).getData());
        verifyNoInteractions(users);
    }

    @Test
    void malformedMemberFailsBeforeProfileReadAndSqlFailureIsNotSwallowed() {
        doReturn(new Blog().setId(86L)).when(service).getById(86L);
        when(redis.opsForZSet()).thenReturn(zsets);
        when(zsets.range("blog:liked:86", 0, 4)).thenReturn(Collections.singleton("not-a-long"));
        assertThrows(NumberFormatException.class, () -> service.queryBlogLikes(86L));
        verifyNoInteractions(users);
        when(zsets.range("blog:liked:86", 0, 4)).thenReturn(Collections.singleton("9"));
        IllegalStateException failure = new IllegalStateException("synthetic SQL failure");
        when(users.findLikers(Collections.singletonList(9L))).thenThrow(failure);
        assertSame(failure, assertThrows(IllegalStateException.class, () -> service.queryBlogLikes(86L)));
        verify(users).findLikers(Collections.singletonList(9L)); verifyNoMoreInteractions(users);
    }
}
