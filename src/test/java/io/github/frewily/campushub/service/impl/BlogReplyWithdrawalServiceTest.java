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

class BlogReplyWithdrawalServiceTest {
    private static final long REPLY_ID = 9007199254740995L;
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
        return new BlogComments().setId(REPLY_ID).setBlogId(BLOG_ID);
    }

    private BlogComments current(int status) {
        return new BlogComments().setId(REPLY_ID).setBlogId(BLOG_ID).setStatus(status);
    }

    private void prepareEligibleReply(int status) {
        when(mapper.findOwnedDirectReply(REPLY_ID, USER_ID)).thenReturn(candidate());
        when(mapper.lockBlog(BLOG_ID)).thenReturn(BLOG_ID);
        when(mapper.lockOwnedDirectReply(REPLY_ID, BLOG_ID, USER_ID)).thenReturn(current(status));
    }

    private void assertError(ErrorCode expected, org.junit.jupiter.api.function.Executable action) {
        BusinessException error = assertThrows(BusinessException.class, action);
        assertEquals(expected, error.getErrorCode());
    }

    @Test
    void withdrawUsesOwnerLookupThenLocksBlogBeforeCurrentReplyAndUpdatesCounter() {
        prepareEligibleReply(0);
        when(mapper.withdrawDirectReply(REPLY_ID, BLOG_ID, USER_ID)).thenReturn(1);
        when(mapper.decrementCommentCount(BLOG_ID)).thenReturn(1);

        assertDoesNotThrow(() -> service.withdrawReply(REPLY_ID));

        InOrder order = inOrder(mapper);
        order.verify(mapper).findOwnedDirectReply(REPLY_ID, USER_ID);
        order.verify(mapper).lockBlog(BLOG_ID);
        order.verify(mapper).lockOwnedDirectReply(REPLY_ID, BLOG_ID, USER_ID);
        order.verify(mapper).withdrawDirectReply(REPLY_ID, BLOG_ID, USER_ID);
        order.verify(mapper).decrementCommentCount(BLOG_ID);
        verifyNoMoreInteractions(mapper);
    }

    @Test
    void validatesReplyIdAndAuthenticatedOwnerBeforeDatabaseAccess() {
        for (Long id : new Long[]{null, 0L, -1L}) {
            assertError(ErrorCode.VALIDATION_FAILED, () -> service.withdrawReply(id));
        }
        UserHolder.removeUser();
        assertError(ErrorCode.AUTHENTICATION_FAILED, () -> service.withdrawReply(REPLY_ID));
        UserDTO invalid = new UserDTO();
        invalid.setId(null);
        UserHolder.saveUser(invalid);
        assertError(ErrorCode.AUTHENTICATION_FAILED, () -> service.withdrawReply(REPLY_ID));
        invalid = new UserDTO();
        invalid.setId(0L);
        UserHolder.saveUser(invalid);
        assertError(ErrorCode.AUTHENTICATION_FAILED, () -> service.withdrawReply(REPLY_ID));
        invalid = new UserDTO();
        invalid.setId(-USER_ID);
        UserHolder.saveUser(invalid);
        assertError(ErrorCode.AUTHENTICATION_FAILED, () -> service.withdrawReply(REPLY_ID));
        verifyNoInteractions(mapper);
    }

    @Test
    void foreignOrIneligibleReplyIsNotFoundWithoutLockOrMutation() {
        when(mapper.findOwnedDirectReply(REPLY_ID, USER_ID)).thenReturn(null);

        assertError(ErrorCode.NOT_FOUND, () -> service.withdrawReply(REPLY_ID));

        verify(mapper).findOwnedDirectReply(REPLY_ID, USER_ID);
        verifyNoMoreInteractions(mapper);
    }

    @Test
    void missingBlogOrChangedCurrentReplyIsNotFoundWithoutMutation() {
        when(mapper.findOwnedDirectReply(REPLY_ID, USER_ID)).thenReturn(candidate());
        when(mapper.lockBlog(BLOG_ID)).thenReturn(null);
        assertError(ErrorCode.NOT_FOUND, () -> service.withdrawReply(REPLY_ID));
        verify(mapper).findOwnedDirectReply(REPLY_ID, USER_ID);
        verify(mapper).lockBlog(BLOG_ID);
        verifyNoMoreInteractions(mapper);

        reset(mapper);
        when(mapper.findOwnedDirectReply(REPLY_ID, USER_ID)).thenReturn(candidate());
        when(mapper.lockBlog(BLOG_ID)).thenReturn(BLOG_ID);
        when(mapper.lockOwnedDirectReply(REPLY_ID, BLOG_ID, USER_ID)).thenReturn(null);
        assertError(ErrorCode.NOT_FOUND, () -> service.withdrawReply(REPLY_ID));
        verify(mapper).findOwnedDirectReply(REPLY_ID, USER_ID);
        verify(mapper).lockBlog(BLOG_ID);
        verify(mapper).lockOwnedDirectReply(REPLY_ID, BLOG_ID, USER_ID);
        verifyNoMoreInteractions(mapper);
    }

    @Test
    void alreadyWithdrawnReplyIsIdempotentAndDoesNotWriteOrChangeCount() {
        prepareEligibleReply(3);

        assertDoesNotThrow(() -> service.withdrawReply(REPLY_ID));

        verify(mapper).findOwnedDirectReply(REPLY_ID, USER_ID);
        verify(mapper).lockBlog(BLOG_ID);
        verify(mapper).lockOwnedDirectReply(REPLY_ID, BLOG_ID, USER_ID);
        verify(mapper, never()).withdrawDirectReply(anyLong(), anyLong(), anyLong());
        verify(mapper, never()).decrementCommentCount(anyLong());
        verifyNoMoreInteractions(mapper);
    }

    @Test
    void zeroUpdateOrCounterResultFailsAndCannotReportSuccess() {
        prepareEligibleReply(0);
        when(mapper.withdrawDirectReply(REPLY_ID, BLOG_ID, USER_ID)).thenReturn(0);
        assertError(ErrorCode.OPERATION_FAILED, () -> service.withdrawReply(REPLY_ID));
        verify(mapper, never()).decrementCommentCount(anyLong());

        reset(mapper);
        prepareEligibleReply(0);
        when(mapper.withdrawDirectReply(REPLY_ID, BLOG_ID, USER_ID)).thenReturn(1);
        when(mapper.decrementCommentCount(BLOG_ID)).thenReturn(0);
        assertError(ErrorCode.OPERATION_FAILED, () -> service.withdrawReply(REPLY_ID));
        verify(mapper).withdrawDirectReply(REPLY_ID, BLOG_ID, USER_ID);
        verify(mapper).decrementCommentCount(BLOG_ID);
    }

    @Test
    void databaseFailuresBecomeGenericInternalErrorsWithoutDriverCause() {
        when(mapper.findOwnedDirectReply(REPLY_ID, USER_ID)).thenThrow(
                new DataAccessResourceFailureException("private SQL and credentials"));
        assertGenericInternalError();

        reset(mapper);
        prepareEligibleReply(0);
        when(mapper.withdrawDirectReply(REPLY_ID, BLOG_ID, USER_ID)).thenThrow(
                new DataAccessResourceFailureException("private write details"));
        assertGenericInternalError();

        reset(mapper);
        prepareEligibleReply(0);
        when(mapper.withdrawDirectReply(REPLY_ID, BLOG_ID, USER_ID)).thenReturn(1);
        when(mapper.decrementCommentCount(BLOG_ID)).thenThrow(
                new DataAccessResourceFailureException("private counter details"));
        assertGenericInternalError();
    }

    private void assertGenericInternalError() {
        BusinessException error = assertThrows(BusinessException.class, () -> service.withdrawReply(REPLY_ID));
        assertEquals(ErrorCode.INTERNAL_ERROR, error.getErrorCode());
        assertNull(error.getCause());
        assertFalse(error.getMessage().contains("private"));
    }
}
