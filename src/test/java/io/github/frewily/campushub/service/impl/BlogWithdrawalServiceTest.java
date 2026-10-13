package io.github.frewily.campushub.service.impl;

import io.github.frewily.campushub.dto.UserDTO;
import io.github.frewily.campushub.entity.Blog;
import io.github.frewily.campushub.exception.BusinessException;
import io.github.frewily.campushub.exception.ErrorCode;
import io.github.frewily.campushub.mapper.BlogMapper;
import io.github.frewily.campushub.service.IFollowService;
import io.github.frewily.campushub.service.IUserService;
import io.github.frewily.campushub.utils.UserHolder;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.test.util.ReflectionTestUtils;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class BlogWithdrawalServiceTest {
    private static final long BLOG_ID = 9007199254740997L;
    private static final long OWNER_ID = 9007199254740993L;

    private BlogMapper mapper;
    private IUserService userService;
    private IFollowService followService;
    private StringRedisTemplate stringRedisTemplate;
    private BlogServiceImpl service;

    @BeforeEach
    void setUp() {
        mapper = mock(BlogMapper.class);
        userService = mock(IUserService.class);
        followService = mock(IFollowService.class);
        stringRedisTemplate = mock(StringRedisTemplate.class);
        service = new BlogServiceImpl();
        ReflectionTestUtils.setField(service, "baseMapper", mapper);
        ReflectionTestUtils.setField(service, "userService", userService);
        ReflectionTestUtils.setField(service, "followService", followService);
        ReflectionTestUtils.setField(service, "stringRedisTemplate", stringRedisTemplate);
        saveUser(OWNER_ID);
    }

    @AfterEach
    void clearUser() {
        UserHolder.removeUser();
    }

    private static Blog stored(int withdrawn) {
        Blog blog = new Blog().setId(BLOG_ID).setUserId(OWNER_ID).setTitle("kept title")
                .setImages("kept images").setContent("kept content").setComments(17);
        return blog.setWithdrawn(withdrawn);
    }

    private static void saveUser(Long id) {
        UserDTO user = new UserDTO();
        user.setId(id);
        UserHolder.saveUser(user);
    }

    private void assertError(ErrorCode expected, org.junit.jupiter.api.function.Executable action) {
        BusinessException error = assertThrows(BusinessException.class, action);
        assertEquals(expected, error.getErrorCode());
    }

    private void assertNoOtherResourceInteractions() {
        verifyNoInteractions(userService, followService, stringRedisTemplate);
    }

    @Test
    void validatesPositiveBlogIdAndAuthenticatedPositiveUserBeforeMapperAccess() {
        for (Long id : new Long[]{null, 0L, -1L}) {
            assertError(ErrorCode.VALIDATION_FAILED, () -> service.withdrawBlog(id));
        }
        for (Long id : new Long[]{null, 0L, -1L}) {
            saveUser(id);
            assertError(ErrorCode.AUTHENTICATION_FAILED, () -> service.withdrawBlog(BLOG_ID));
        }
        UserHolder.removeUser();
        assertError(ErrorCode.AUTHENTICATION_FAILED, () -> service.withdrawBlog(BLOG_ID));

        verifyNoInteractions(mapper);
        assertNoOtherResourceInteractions();
    }

    @Test
    void locksCurrentBlogThenWithdrawsOnlyOwnedRowAndLeavesUnrelatedServicesUntouched() {
        when(mapper.findBlogForAuthorWithdrawal(BLOG_ID)).thenReturn(stored(0));
        when(mapper.withdrawOwnedBlog(BLOG_ID, OWNER_ID)).thenReturn(1);

        assertDoesNotThrow(() -> service.withdrawBlog(BLOG_ID));

        org.mockito.InOrder order = inOrder(mapper);
        order.verify(mapper).findBlogForAuthorWithdrawal(BLOG_ID);
        order.verify(mapper).withdrawOwnedBlog(BLOG_ID, OWNER_ID);
        verifyNoMoreInteractions(mapper);
        assertNoOtherResourceInteractions();
    }

    @Test
    void missingBlogIsNotFoundAndForeignOwnerIncludingAdminIdentityIsForbidden() {
        when(mapper.findBlogForAuthorWithdrawal(BLOG_ID)).thenReturn(null);
        assertError(ErrorCode.NOT_FOUND, () -> service.withdrawBlog(BLOG_ID));
        verify(mapper).findBlogForAuthorWithdrawal(BLOG_ID);
        verifyNoMoreInteractions(mapper);

        reset(mapper);
        when(mapper.findBlogForAuthorWithdrawal(BLOG_ID))
                .thenReturn(stored(0));
        saveUser(42L); // An ADMIN role does not grant service-level ownership override.
        assertError(ErrorCode.AUTHORIZATION_FAILED, () -> service.withdrawBlog(BLOG_ID));
        verify(mapper).findBlogForAuthorWithdrawal(BLOG_ID);
        verifyNoMoreInteractions(mapper);
        assertNoOtherResourceInteractions();
    }

    @Test
    void repeatedWithdrawalIsIdempotentAndDoesNotRunAnUpdate() {
        when(mapper.findBlogForAuthorWithdrawal(BLOG_ID)).thenReturn(stored(1));

        service.withdrawBlog(BLOG_ID);
        service.withdrawBlog(BLOG_ID);

        verify(mapper, times(2)).findBlogForAuthorWithdrawal(BLOG_ID);
        verify(mapper, never()).withdrawOwnedBlog(anyLong(), anyLong());
        verifyNoMoreInteractions(mapper);
        assertNoOtherResourceInteractions();
    }

    @Test
    void updateMustAffectExactlyOneRow() {
        when(mapper.findBlogForAuthorWithdrawal(BLOG_ID)).thenReturn(stored(0));
        when(mapper.withdrawOwnedBlog(BLOG_ID, OWNER_ID)).thenReturn(0);

        assertError(ErrorCode.OPERATION_FAILED, () -> service.withdrawBlog(BLOG_ID));

        verify(mapper).findBlogForAuthorWithdrawal(BLOG_ID);
        verify(mapper).withdrawOwnedBlog(BLOG_ID, OWNER_ID);
        verifyNoMoreInteractions(mapper);
        assertNoOtherResourceInteractions();
    }

    @Test
    void dataAccessFailuresAtLookupAndUpdateBecomeSanitizedInternalErrors() {
        when(mapper.findBlogForAuthorWithdrawal(BLOG_ID)).thenThrow(
                new DataAccessResourceFailureException("private lookup and SQL details"));
        assertGenericInternalError();

        reset(mapper);
        when(mapper.findBlogForAuthorWithdrawal(BLOG_ID)).thenReturn(stored(0));
        when(mapper.withdrawOwnedBlog(BLOG_ID, OWNER_ID)).thenThrow(
                new DataAccessResourceFailureException("private update and driver details"));
        assertGenericInternalError();
        assertNoOtherResourceInteractions();
    }

    private void assertGenericInternalError() {
        BusinessException error = assertThrows(BusinessException.class, () -> service.withdrawBlog(BLOG_ID));
        assertEquals(ErrorCode.INTERNAL_ERROR, error.getErrorCode());
        assertNull(error.getCause());
        assertFalse(error.getMessage().contains("private"));
    }
}
