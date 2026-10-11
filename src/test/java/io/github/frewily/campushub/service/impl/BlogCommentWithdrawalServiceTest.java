package io.github.frewily.campushub.service.impl;

import io.github.frewily.campushub.dto.UserDTO;
import io.github.frewily.campushub.entity.BlogComments;
import io.github.frewily.campushub.exception.BusinessException;
import io.github.frewily.campushub.exception.ErrorCode;
import io.github.frewily.campushub.mapper.BlogCommentsMapper;
import io.github.frewily.campushub.utils.UserHolder;
import org.mockito.InOrder;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.test.util.ReflectionTestUtils;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class BlogCommentWithdrawalServiceTest {
    private static final long COMMENT_ID = 9007199254740995L;
    private static final long BLOG_ID = 9007199254740997L;
    private static final long USER_ID = 9007199254740993L;

    private BlogCommentsMapper mapper;
    private BlogCommentsServiceImpl service;

    @BeforeEach
    void setUp() {
        mapper = mock(BlogCommentsMapper.class);
        service = new BlogCommentsServiceImpl(null);
        ReflectionTestUtils.setField(service, "baseMapper", mapper);
        UserDTO user = new UserDTO();
        user.setId(USER_ID);
        UserHolder.saveUser(user);
    }

    @AfterEach
    void clearUser() {
        UserHolder.removeUser();
    }

    private BlogComments candidate() {
        return new BlogComments().setId(COMMENT_ID).setBlogId(BLOG_ID);
    }

    private BlogComments current(int status) {
        return new BlogComments().setId(COMMENT_ID).setBlogId(BLOG_ID).setStatus(status);
    }

    private void prepareEligibleComment(int status) {
        when(mapper.findOwnedFirstLevelComment(COMMENT_ID, USER_ID)).thenReturn(candidate());
        when(mapper.lockBlog(BLOG_ID)).thenReturn(BLOG_ID);
        when(mapper.lockOwnedFirstLevelComment(COMMENT_ID, BLOG_ID, USER_ID)).thenReturn(current(status));
    }

    private void assertError(ErrorCode expected, org.junit.jupiter.api.function.Executable action) {
        BusinessException error = assertThrows(BusinessException.class, action);
        assertEquals(expected, error.getErrorCode());
    }

    @Test
    void withdrawUsesOwnerLookupThenLocksBlogBeforeCurrentCommentAndUpdatesCounter() {
        prepareEligibleComment(0);
        when(mapper.withdrawFirstLevelComment(COMMENT_ID, BLOG_ID, USER_ID)).thenReturn(1);
        when(mapper.decrementCommentCount(BLOG_ID)).thenReturn(1);

        assertDoesNotThrow(() -> service.withdrawComment(COMMENT_ID));

        InOrder order = inOrder(mapper);
        order.verify(mapper).findOwnedFirstLevelComment(COMMENT_ID, USER_ID);
        order.verify(mapper).lockBlog(BLOG_ID);
        order.verify(mapper).lockOwnedFirstLevelComment(COMMENT_ID, BLOG_ID, USER_ID);
        order.verify(mapper).withdrawFirstLevelComment(COMMENT_ID, BLOG_ID, USER_ID);
        order.verify(mapper).decrementCommentCount(BLOG_ID);
        verifyNoMoreInteractions(mapper);
    }

    @Test
    void validatesCommentIdAndAuthenticatedOwnerBeforeDatabaseAccess() {
        for (Long id : new Long[]{null, 0L, -1L}) {
            assertError(ErrorCode.VALIDATION_FAILED, () -> service.withdrawComment(id));
        }
        UserHolder.removeUser();
        assertError(ErrorCode.AUTHENTICATION_FAILED, () -> service.withdrawComment(COMMENT_ID));
        UserDTO invalid = new UserDTO();
        invalid.setId(null);
        UserHolder.saveUser(invalid);
        assertError(ErrorCode.AUTHENTICATION_FAILED, () -> service.withdrawComment(COMMENT_ID));
        invalid = new UserDTO();
        invalid.setId(0L);
        UserHolder.saveUser(invalid);
        assertError(ErrorCode.AUTHENTICATION_FAILED, () -> service.withdrawComment(COMMENT_ID));
        invalid = new UserDTO();
        invalid.setId(-USER_ID);
        UserHolder.saveUser(invalid);
        assertError(ErrorCode.AUTHENTICATION_FAILED, () -> service.withdrawComment(COMMENT_ID));
        verifyNoInteractions(mapper);
    }

    @Test
    void foreignOrIneligibleCommentIsNotFoundWithoutLockOrMutation() {
        when(mapper.findOwnedFirstLevelComment(COMMENT_ID, USER_ID)).thenReturn(null);

        assertError(ErrorCode.NOT_FOUND, () -> service.withdrawComment(COMMENT_ID));

        verify(mapper).findOwnedFirstLevelComment(COMMENT_ID, USER_ID);
        verifyNoMoreInteractions(mapper);
    }

    @Test
    void missingBlogOrChangedCurrentCommentIsNotFoundWithoutMutation() {
        when(mapper.findOwnedFirstLevelComment(COMMENT_ID, USER_ID)).thenReturn(candidate());
        when(mapper.lockBlog(BLOG_ID)).thenReturn(null);
        assertError(ErrorCode.NOT_FOUND, () -> service.withdrawComment(COMMENT_ID));
        verify(mapper).findOwnedFirstLevelComment(COMMENT_ID, USER_ID);
        verify(mapper).lockBlog(BLOG_ID);
        verifyNoMoreInteractions(mapper);

        reset(mapper);
        when(mapper.findOwnedFirstLevelComment(COMMENT_ID, USER_ID)).thenReturn(candidate());
        when(mapper.lockBlog(BLOG_ID)).thenReturn(BLOG_ID);
        when(mapper.lockOwnedFirstLevelComment(COMMENT_ID, BLOG_ID, USER_ID)).thenReturn(null);
        assertError(ErrorCode.NOT_FOUND, () -> service.withdrawComment(COMMENT_ID));
        verify(mapper).findOwnedFirstLevelComment(COMMENT_ID, USER_ID);
        verify(mapper).lockBlog(BLOG_ID);
        verify(mapper).lockOwnedFirstLevelComment(COMMENT_ID, BLOG_ID, USER_ID);
        verifyNoMoreInteractions(mapper);
    }

    @Test
    void alreadyWithdrawnCommentIsIdempotentAndDoesNotWriteOrChangeCount() {
        prepareEligibleComment(3);

        assertDoesNotThrow(() -> service.withdrawComment(COMMENT_ID));

        verify(mapper).findOwnedFirstLevelComment(COMMENT_ID, USER_ID);
        verify(mapper).lockBlog(BLOG_ID);
        verify(mapper).lockOwnedFirstLevelComment(COMMENT_ID, BLOG_ID, USER_ID);
        verify(mapper, never()).withdrawFirstLevelComment(anyLong(), anyLong(), anyLong());
        verify(mapper, never()).decrementCommentCount(anyLong());
        verifyNoMoreInteractions(mapper);
    }

    @Test
    void zeroUpdateOrCounterResultFailsAndCannotReportSuccess() {
        prepareEligibleComment(0);
        when(mapper.withdrawFirstLevelComment(COMMENT_ID, BLOG_ID, USER_ID)).thenReturn(0);
        assertError(ErrorCode.OPERATION_FAILED, () -> service.withdrawComment(COMMENT_ID));
        verify(mapper, never()).decrementCommentCount(anyLong());

        reset(mapper);
        prepareEligibleComment(0);
        when(mapper.withdrawFirstLevelComment(COMMENT_ID, BLOG_ID, USER_ID)).thenReturn(1);
        when(mapper.decrementCommentCount(BLOG_ID)).thenReturn(0);
        assertError(ErrorCode.OPERATION_FAILED, () -> service.withdrawComment(COMMENT_ID));
        verify(mapper).withdrawFirstLevelComment(COMMENT_ID, BLOG_ID, USER_ID);
        verify(mapper).decrementCommentCount(BLOG_ID);
    }

    @Test
    void databaseFailuresAtEveryMapperBoundaryBecomeGenericErrorsWithoutCause() {
        when(mapper.findOwnedFirstLevelComment(COMMENT_ID, USER_ID)).thenThrow(
                new DataAccessResourceFailureException("private SQL and credentials"));
        assertGenericInternalError();

        reset(mapper);
        when(mapper.findOwnedFirstLevelComment(COMMENT_ID, USER_ID)).thenReturn(candidate());
        when(mapper.lockBlog(BLOG_ID)).thenThrow(new DataAccessResourceFailureException("private blog lock"));
        assertGenericInternalError();

        reset(mapper);
        when(mapper.findOwnedFirstLevelComment(COMMENT_ID, USER_ID)).thenReturn(candidate());
        when(mapper.lockBlog(BLOG_ID)).thenReturn(BLOG_ID);
        when(mapper.lockOwnedFirstLevelComment(COMMENT_ID, BLOG_ID, USER_ID)).thenThrow(
                new DataAccessResourceFailureException("private comment lock"));
        assertGenericInternalError();

        reset(mapper);
        prepareEligibleComment(0);
        when(mapper.withdrawFirstLevelComment(COMMENT_ID, BLOG_ID, USER_ID)).thenThrow(
                new DataAccessResourceFailureException("private write details"));
        assertGenericInternalError();

        reset(mapper);
        prepareEligibleComment(0);
        when(mapper.withdrawFirstLevelComment(COMMENT_ID, BLOG_ID, USER_ID)).thenReturn(1);
        when(mapper.decrementCommentCount(BLOG_ID)).thenThrow(
                new DataAccessResourceFailureException("private counter details"));
        assertGenericInternalError();
    }

    private void assertGenericInternalError() {
        BusinessException error = assertThrows(BusinessException.class, () -> service.withdrawComment(COMMENT_ID));
        assertEquals(ErrorCode.INTERNAL_ERROR, error.getErrorCode());
        assertNull(error.getCause());
        assertFalse(error.getMessage().contains("private"));
    }
}
