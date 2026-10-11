package io.github.frewily.campushub.service.impl;

import io.github.frewily.campushub.dto.UserDTO;
import io.github.frewily.campushub.dto.request.BlogCommentHideRequest;
import io.github.frewily.campushub.entity.BlogComments;
import io.github.frewily.campushub.exception.BusinessException;
import io.github.frewily.campushub.exception.ErrorCode;
import io.github.frewily.campushub.mapper.BlogCommentsMapper;
import io.github.frewily.campushub.utils.UserHolder;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.test.util.ReflectionTestUtils;

import javax.validation.Validation;
import javax.validation.Validator;
import javax.validation.ValidatorFactory;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class BlogCommentModerationServiceTest {
    private static final long COMMENT_ID = 9007199254740995L;
    private static final long BLOG_ID = 9007199254740997L;
    private static final long OPERATOR_ID = 9007199254740993L;
    private static final long AUTHOR_ID = 9007199254740999L;
    private static final long AUDIT_ID = 9007199254741001L;

    private static ValidatorFactory validatorFactory;
    private BlogCommentsMapper mapper;
    private BlogCommentsServiceImpl service;

    @BeforeAll
    static void createValidator() {
        validatorFactory = Validation.buildDefaultValidatorFactory();
    }

    @AfterAll
    static void closeValidator() {
        validatorFactory.close();
    }

    @BeforeEach
    void setUp() {
        Validator validator = validatorFactory.getValidator();
        mapper = mock(BlogCommentsMapper.class);
        service = new BlogCommentsServiceImpl(validator);
        ReflectionTestUtils.setField(service, "baseMapper", mapper);
        UserDTO operator = new UserDTO();
        operator.setId(OPERATOR_ID);
        UserHolder.saveUser(operator);
    }

    @AfterEach
    void clearUser() {
        UserHolder.removeUser();
    }

    private BlogCommentHideRequest request(String reason) {
        BlogCommentHideRequest request = new BlogCommentHideRequest();
        request.setReason(reason);
        return request;
    }

    private BlogComments candidate() {
        return new BlogComments().setId(COMMENT_ID).setBlogId(BLOG_ID);
    }

    private BlogComments current(int status) {
        return new BlogComments().setId(COMMENT_ID).setBlogId(BLOG_ID).setStatus(status);
    }

    private void prepareEligibleComment(int status) {
        when(mapper.findModeratableFirstLevelComment(COMMENT_ID)).thenReturn(candidate());
        when(mapper.lockBlog(BLOG_ID)).thenReturn(BLOG_ID);
        when(mapper.lockModeratableFirstLevelComment(COMMENT_ID, BLOG_ID)).thenReturn(current(status));
    }

    private void assertError(ErrorCode expected, org.junit.jupiter.api.function.Executable action) {
        BusinessException error = assertThrows(BusinessException.class, action);
        assertEquals(expected, error.getErrorCode());
    }

    @Test
    void firstHideAllowsForeignAuthorAndUsesPostThenCommentLockOrder() {
        BlogComments foreignAuthorsComment = current(0).setUserId(AUTHOR_ID);
        when(mapper.findModeratableFirstLevelComment(COMMENT_ID)).thenReturn(candidate());
        when(mapper.lockBlog(BLOG_ID)).thenReturn(BLOG_ID);
        when(mapper.lockModeratableFirstLevelComment(COMMENT_ID, BLOG_ID)).thenReturn(foreignAuthorsComment);
        when(mapper.hideFirstLevelComment(COMMENT_ID, BLOG_ID)).thenReturn(1);
        when(mapper.decrementCommentCount(BLOG_ID)).thenReturn(1);
        when(mapper.insertFirstLevelHideAudit(COMMENT_ID, BLOG_ID, OPERATOR_ID, "policy violation"))
                .thenReturn(1);

        assertDoesNotThrow(() -> service.hideComment(COMMENT_ID, request("policy violation")));

        InOrder order = inOrder(mapper);
        order.verify(mapper).findModeratableFirstLevelComment(COMMENT_ID);
        order.verify(mapper).lockBlog(BLOG_ID);
        order.verify(mapper).lockModeratableFirstLevelComment(COMMENT_ID, BLOG_ID);
        order.verify(mapper).hideFirstLevelComment(COMMENT_ID, BLOG_ID);
        order.verify(mapper).decrementCommentCount(BLOG_ID);
        order.verify(mapper).insertFirstLevelHideAudit(COMMENT_ID, BLOG_ID, OPERATOR_ID, "policy violation");
        verifyNoMoreInteractions(mapper);
    }

    @Test
    void invalidPathRequestOrOperatorCannotReachMapper() {
        for (Long id : new Long[]{null, 0L, -1L}) {
            assertError(ErrorCode.VALIDATION_FAILED, () -> service.hideComment(id, request("reason")));
        }
        assertError(ErrorCode.VALIDATION_FAILED, () -> service.hideComment(COMMENT_ID, null));
        assertError(ErrorCode.VALIDATION_FAILED, () -> service.hideComment(COMMENT_ID, request(null)));
        assertError(ErrorCode.VALIDATION_FAILED, () -> service.hideComment(COMMENT_ID, request(" \n\t")));
        assertError(ErrorCode.VALIDATION_FAILED,
                () -> service.hideComment(COMMENT_ID, request(repeat('x', 256))));

        UserHolder.removeUser();
        assertError(ErrorCode.AUTHENTICATION_FAILED, () -> service.hideComment(COMMENT_ID, request("reason")));
        for (Long id : new Long[]{null, 0L, -OPERATOR_ID}) {
            UserDTO invalid = new UserDTO();
            invalid.setId(id);
            UserHolder.saveUser(invalid);
            assertError(ErrorCode.AUTHENTICATION_FAILED,
                    () -> service.hideComment(COMMENT_ID, request("reason")));
        }
        verifyNoInteractions(mapper);
    }

    @Test
    void ineligibleCommentIsNotFoundBeforeLocksOrWrites() {
        when(mapper.findModeratableFirstLevelComment(COMMENT_ID)).thenReturn(null);

        assertError(ErrorCode.NOT_FOUND, () -> service.hideComment(COMMENT_ID, request("reason")));

        verify(mapper).findModeratableFirstLevelComment(COMMENT_ID);
        verifyNoMoreInteractions(mapper);
    }

    @Test
    void missingBlogOrNoLongerModeratableCommentIsNotFoundWithoutWrites() {
        when(mapper.findModeratableFirstLevelComment(COMMENT_ID)).thenReturn(candidate());
        when(mapper.lockBlog(BLOG_ID)).thenReturn(null);
        assertError(ErrorCode.NOT_FOUND, () -> service.hideComment(COMMENT_ID, request("reason")));
        verify(mapper).findModeratableFirstLevelComment(COMMENT_ID);
        verify(mapper).lockBlog(BLOG_ID);
        verifyNoMoreInteractions(mapper);

        reset(mapper);
        when(mapper.findModeratableFirstLevelComment(COMMENT_ID)).thenReturn(candidate());
        when(mapper.lockBlog(BLOG_ID)).thenReturn(BLOG_ID);
        when(mapper.lockModeratableFirstLevelComment(COMMENT_ID, BLOG_ID)).thenReturn(null);
        assertError(ErrorCode.NOT_FOUND, () -> service.hideComment(COMMENT_ID, request("reason")));
        verify(mapper).findModeratableFirstLevelComment(COMMENT_ID);
        verify(mapper).lockBlog(BLOG_ID);
        verify(mapper).lockModeratableFirstLevelComment(COMMENT_ID, BLOG_ID);
        verifyNoMoreInteractions(mapper);
    }

    @Test
    void auditedHiddenCommentIsAnIdempotentSuccessAndPreservesFirstReason() {
        prepareEligibleComment(2);
        when(mapper.findFirstLevelHideAudit(COMMENT_ID, BLOG_ID)).thenReturn(AUDIT_ID);

        assertDoesNotThrow(() -> service.hideComment(COMMENT_ID, request("later reason")));

        verify(mapper).findModeratableFirstLevelComment(COMMENT_ID);
        verify(mapper).lockBlog(BLOG_ID);
        verify(mapper).lockModeratableFirstLevelComment(COMMENT_ID, BLOG_ID);
        verify(mapper).findFirstLevelHideAudit(COMMENT_ID, BLOG_ID);
        verify(mapper, never()).hideFirstLevelComment(anyLong(), anyLong());
        verify(mapper, never()).decrementCommentCount(anyLong());
        verify(mapper, never()).insertFirstLevelHideAudit(anyLong(), anyLong(), anyLong(), anyString());
        verifyNoMoreInteractions(mapper);
    }

    @Test
    void hiddenCommentWithoutThisUseCasesAuditIsNotFound() {
        prepareEligibleComment(2);
        when(mapper.findFirstLevelHideAudit(COMMENT_ID, BLOG_ID)).thenReturn(null);

        assertError(ErrorCode.NOT_FOUND, () -> service.hideComment(COMMENT_ID, request("reason")));

        verify(mapper).findModeratableFirstLevelComment(COMMENT_ID);
        verify(mapper).lockBlog(BLOG_ID);
        verify(mapper).lockModeratableFirstLevelComment(COMMENT_ID, BLOG_ID);
        verify(mapper).findFirstLevelHideAudit(COMMENT_ID, BLOG_ID);
        verify(mapper, never()).hideFirstLevelComment(anyLong(), anyLong());
        verify(mapper, never()).decrementCommentCount(anyLong());
        verify(mapper, never()).insertFirstLevelHideAudit(anyLong(), anyLong(), anyLong(), anyString());
        verifyNoMoreInteractions(mapper);
    }

    @Test
    void zeroAffectedRowsFailAndShortCircuitLaterWrites() {
        prepareEligibleComment(0);
        when(mapper.hideFirstLevelComment(COMMENT_ID, BLOG_ID)).thenReturn(0);
        assertError(ErrorCode.OPERATION_FAILED, () -> service.hideComment(COMMENT_ID, request("reason")));
        verify(mapper, never()).decrementCommentCount(anyLong());
        verify(mapper, never()).insertFirstLevelHideAudit(anyLong(), anyLong(), anyLong(), anyString());

        reset(mapper);
        prepareEligibleComment(0);
        when(mapper.hideFirstLevelComment(COMMENT_ID, BLOG_ID)).thenReturn(1);
        when(mapper.decrementCommentCount(BLOG_ID)).thenReturn(0);
        assertError(ErrorCode.OPERATION_FAILED, () -> service.hideComment(COMMENT_ID, request("reason")));
        verify(mapper).decrementCommentCount(BLOG_ID);
        verify(mapper, never()).insertFirstLevelHideAudit(anyLong(), anyLong(), anyLong(), anyString());

        reset(mapper);
        prepareEligibleComment(0);
        when(mapper.hideFirstLevelComment(COMMENT_ID, BLOG_ID)).thenReturn(1);
        when(mapper.decrementCommentCount(BLOG_ID)).thenReturn(1);
        when(mapper.insertFirstLevelHideAudit(COMMENT_ID, BLOG_ID, OPERATOR_ID, "reason")).thenReturn(0);
        assertError(ErrorCode.OPERATION_FAILED, () -> service.hideComment(COMMENT_ID, request("reason")));
        verify(mapper).insertFirstLevelHideAudit(COMMENT_ID, BLOG_ID, OPERATOR_ID, "reason");
    }

    @Test
    void databaseFailuresAtEveryMapperBoundaryBecomeSanitizedInternalErrors() {
        when(mapper.findModeratableFirstLevelComment(COMMENT_ID)).thenThrow(
                new DataAccessResourceFailureException("private candidate SQL and credentials"));
        assertGenericInternalError();

        reset(mapper);
        when(mapper.findModeratableFirstLevelComment(COMMENT_ID)).thenReturn(candidate());
        when(mapper.lockBlog(BLOG_ID)).thenThrow(new DataAccessResourceFailureException("private blog lock"));
        assertGenericInternalError();

        reset(mapper);
        when(mapper.findModeratableFirstLevelComment(COMMENT_ID)).thenReturn(candidate());
        when(mapper.lockBlog(BLOG_ID)).thenReturn(BLOG_ID);
        when(mapper.lockModeratableFirstLevelComment(COMMENT_ID, BLOG_ID)).thenThrow(
                new DataAccessResourceFailureException("private current comment lock"));
        assertGenericInternalError();

        reset(mapper);
        prepareEligibleComment(2);
        when(mapper.findFirstLevelHideAudit(COMMENT_ID, BLOG_ID)).thenThrow(
                new DataAccessResourceFailureException("private current audit read"));
        assertGenericInternalError();

        reset(mapper);
        prepareEligibleComment(0);
        when(mapper.hideFirstLevelComment(COMMENT_ID, BLOG_ID)).thenThrow(
                new DataAccessResourceFailureException("private hide update"));
        assertGenericInternalError();

        reset(mapper);
        prepareEligibleComment(0);
        when(mapper.hideFirstLevelComment(COMMENT_ID, BLOG_ID)).thenReturn(1);
        when(mapper.decrementCommentCount(BLOG_ID)).thenThrow(
                new DataAccessResourceFailureException("private counter update"));
        assertGenericInternalError();

        reset(mapper);
        prepareEligibleComment(0);
        when(mapper.hideFirstLevelComment(COMMENT_ID, BLOG_ID)).thenReturn(1);
        when(mapper.decrementCommentCount(BLOG_ID)).thenReturn(1);
        when(mapper.insertFirstLevelHideAudit(COMMENT_ID, BLOG_ID, OPERATOR_ID, "reason")).thenThrow(
                new DataAccessResourceFailureException("private audit insert"));
        assertGenericInternalError();
    }

    private void assertGenericInternalError() {
        BusinessException error = assertThrows(BusinessException.class,
                () -> service.hideComment(COMMENT_ID, request("reason")));
        assertEquals(ErrorCode.INTERNAL_ERROR, error.getErrorCode());
        assertNull(error.getCause());
        assertFalse(error.getMessage().contains("private"));
    }

    private String repeat(char value, int count) {
        StringBuilder result = new StringBuilder(count);
        for (int i = 0; i < count; i++) result.append(value);
        return result.toString();
    }
}
