package io.github.frewily.campushub.service.impl;

import com.baomidou.mybatisplus.extension.conditions.query.QueryChainWrapper;
import io.github.frewily.campushub.dto.UserDTO;
import io.github.frewily.campushub.entity.Blog;
import io.github.frewily.campushub.entity.Follow;
import io.github.frewily.campushub.exception.BusinessException;
import io.github.frewily.campushub.exception.ErrorCode;
import io.github.frewily.campushub.mapper.BlogMapper;
import io.github.frewily.campushub.service.IFollowService;
import io.github.frewily.campushub.service.BlogFeedDeliveryService;
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
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
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
    @Mock
    private BlogFeedDeliveryService deliveryService;

    private BlogServiceImpl blogService;

    @BeforeEach
    void setUp() {
        // Unit-only transaction flags; the explicit MySQL IT verifies real Spring transaction behavior.
        TransactionSynchronizationManager.initSynchronization();
        TransactionSynchronizationManager.setActualTransactionActive(true);
        blogService = new BlogServiceImpl();
        ReflectionTestUtils.setField(blogService, "baseMapper", blogMapper);
        ReflectionTestUtils.setField(blogService, "followService", followService);
        ReflectionTestUtils.setField(blogService, "stringRedisTemplate", stringRedisTemplate);
        ReflectionTestUtils.setField(blogService, "blogFeedDeliveryService", deliveryService);

        UserDTO author = new UserDTO();
        author.setId(7L);
        UserHolder.saveUser(author);
    }

    @AfterEach
    void tearDown() {
        TransactionSynchronizationManager.clear();
        UserHolder.removeUser();
    }

    @Test
    void shouldRejectUnproxiedPublicationBeforeAnyPersistence() {
        TransactionSynchronizationManager.clear();
        assertThrows(IllegalStateException.class,
                () -> blogService.saveBlog(new Blog().setTitle("must require transaction")));
        verifyNoInteractions(blogMapper, followService, stringRedisTemplate, deliveryService);
    }

    @Test
    void shouldRejectPublicationJoinedToReadOnlyTransactionBeforePersistence() {
        TransactionSynchronizationManager.setCurrentTransactionReadOnly(true);
        assertThrows(IllegalStateException.class,
                () -> blogService.saveBlog(new Blog().setTitle("must require writable transaction")));
        verifyNoInteractions(blogMapper, followService, stringRedisTemplate, deliveryService);
    }

    @Test
    void shouldRejectMissingOrInvalidAuthenticatedAuthorBeforePersistence() {
        for (Long id : new Long[]{null, 0L, -1L}) {
            UserDTO identity = new UserDTO();
            identity.setId(id);
            UserHolder.saveUser(identity);
            assertEquals(ErrorCode.AUTHENTICATION_FAILED, assertThrows(BusinessException.class,
                    () -> blogService.saveBlog(new Blog().setTitle("must authenticate"))).getErrorCode());
        }
        UserHolder.removeUser();
        assertEquals(ErrorCode.AUTHENTICATION_FAILED, assertThrows(BusinessException.class,
                () -> blogService.saveBlog(new Blog().setTitle("must authenticate"))).getErrorCode());
        verifyNoInteractions(blogMapper, followService, stringRedisTemplate, deliveryService);
    }

    @Test
    void shouldEnqueuePostForEachFollowersOwnFeedWithoutWritingRedisInThePublisher() {
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

        Blog blog = new Blog().setShopId(1L).setTitle("Campus post").setUserId(999L).setWithdrawn(1);
        blogService.saveBlog(blog);

        verify(deliveryService).enqueue(eq(100L), eq(Arrays.asList(42L, 84L)), anyLong());
        verifyNoInteractions(stringRedisTemplate);
        assertEquals(7L, blog.getUserId());
        assertEquals(0, blog.getWithdrawn());
    }

    @Test
    void shouldAcceptNullShopIdAndEnqueueTheSameRecipientSnapshot() {
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

        Blog blog = new Blog().setShopId(null).setTitle("Campus post").setUserId(999L);
        blogService.saveBlog(blog);

        verify(deliveryService).enqueue(eq(101L), eq(Arrays.asList(42L, 84L)), anyLong());
        verifyNoInteractions(stringRedisTemplate);
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
