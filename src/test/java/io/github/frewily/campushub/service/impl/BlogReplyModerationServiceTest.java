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
import org.springframework.transaction.support.TransactionSynchronizationManager;

import javax.validation.Validation;
import javax.validation.Validator;
import javax.validation.ValidatorFactory;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class BlogReplyModerationServiceTest {
    private static final long REPLY_ID = 9007199254740995L;
    private static final long BLOG_ID = 9007199254740997L;
    private static final long ROOT_ID = 9007199254740999L;
    private static final long OPERATOR_ID = 9007199254741001L;
    private static final long AUDIT_ID = 9007199254741003L;

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
        beginWritableTransactionState();
    }

    @AfterEach
    void cleanThreadLocals() {
        UserHolder.removeUser();
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.clearSynchronization();
        }
        TransactionSynchronizationManager.setActualTransactionActive(false);
        TransactionSynchronizationManager.setCurrentTransactionReadOnly(false);
    }

    private void beginWritableTransactionState() {
        TransactionSynchronizationManager.setActualTransactionActive(true);
        TransactionSynchronizationManager.setCurrentTransactionReadOnly(false);
        TransactionSynchronizationManager.initSynchronization();
    }

    private BlogCommentHideRequest request(String reason) {
        BlogCommentHideRequest request = new BlogCommentHideRequest();
        request.setReason(reason);
        return request;
    }

    private BlogComments candidate() {
        return new BlogComments().setId(REPLY_ID).setBlogId(BLOG_ID)
                .setParentId(ROOT_ID).setAnswerId(ROOT_ID);
    }

    private BlogComments current(int status) {
        return new BlogComments().setId(REPLY_ID).setBlogId(BLOG_ID)
                .setParentId(ROOT_ID).setAnswerId(ROOT_ID).setStatus(status);
    }

    private void prepareEligibleReply(int status) {
        when(mapper.findModeratableDirectReply(REPLY_ID)).thenReturn(candidate());
        when(mapper.lockBlog(BLOG_ID)).thenReturn(BLOG_ID);
        when(mapper.lockDirectReplyRoot(ROOT_ID, BLOG_ID)).thenReturn(ROOT_ID);
        when(mapper.lockModeratableDirectReply(REPLY_ID, BLOG_ID, ROOT_ID)).thenReturn(current(status));
    }

    private void assertError(ErrorCode expected, org.junit.jupiter.api.function.Executable action) {
        BusinessException error = assertThrows(BusinessException.class, action);
        assertEquals(expected, error.getErrorCode());
    }

    @Test
    void firstHideLocksPostThenRootThenReplyAndWritesHideCounterAndAuditInOrder() {
        prepareEligibleReply(0);
        when(mapper.hideDirectReply(REPLY_ID, BLOG_ID, ROOT_ID)).thenReturn(1);
        when(mapper.decrementCommentCount(BLOG_ID)).thenReturn(1);
        when(mapper.insertDirectReplyHideAudit(REPLY_ID, BLOG_ID, ROOT_ID, OPERATOR_ID,
                "policy violation")).thenReturn(1);

        assertDoesNotThrow(() -> service.hideReply(REPLY_ID, request("policy violation")));

        InOrder order = inOrder(mapper);
        order.verify(mapper).findModeratableDirectReply(REPLY_ID);
        order.verify(mapper).lockBlog(BLOG_ID);
        order.verify(mapper).lockDirectReplyRoot(ROOT_ID, BLOG_ID);
        order.verify(mapper).lockModeratableDirectReply(REPLY_ID, BLOG_ID, ROOT_ID);
        order.verify(mapper).hideDirectReply(REPLY_ID, BLOG_ID, ROOT_ID);
        order.verify(mapper).decrementCommentCount(BLOG_ID);
        order.verify(mapper).insertDirectReplyHideAudit(REPLY_ID, BLOG_ID, ROOT_ID,
                OPERATOR_ID, "policy violation");
        verifyNoMoreInteractions(mapper);
    }

    @Test
    void validatesPathRequestAndPositiveOperatorBeforeDatabaseAccess() {
        for (Long id : new Long[]{null, 0L, -1L}) {
            assertError(ErrorCode.VALIDATION_FAILED, () -> service.hideReply(id, request("reason")));
        }
        for (BlogCommentHideRequest invalid : new BlogCommentHideRequest[]{null, request(null),
                request(" \n\t"), request(repeat('x', 256))}) {
            assertError(ErrorCode.VALIDATION_FAILED, () -> service.hideReply(REPLY_ID, invalid));
        }

        UserHolder.removeUser();
        assertError(ErrorCode.AUTHENTICATION_FAILED, () -> service.hideReply(REPLY_ID, request("reason")));
        for (Long id : new Long[]{null, 0L, -OPERATOR_ID}) {
            UserDTO invalid = new UserDTO();
            invalid.setId(id);
            UserHolder.saveUser(invalid);
            assertError(ErrorCode.AUTHENTICATION_FAILED,
                    () -> service.hideReply(REPLY_ID, request("reason")));
        }
        verifyNoInteractions(mapper);
    }

    @Test
    void requiresActualWritableSynchronizedTransactionBeforeFirstMapperCall() {
        TransactionSynchronizationManager.clearSynchronization();
        TransactionSynchronizationManager.setActualTransactionActive(false);
        assertThrows(IllegalStateException.class,
                () -> service.hideReply(REPLY_ID, request("reason")));
        verifyNoInteractions(mapper);

        beginWritableTransactionState();
        TransactionSynchronizationManager.setCurrentTransactionReadOnly(true);
        assertThrows(IllegalStateException.class,
                () -> service.hideReply(REPLY_ID, request("reason")));
        verifyNoInteractions(mapper);

        TransactionSynchronizationManager.setCurrentTransactionReadOnly(false);
        TransactionSynchronizationManager.clearSynchronization();
        assertThrows(IllegalStateException.class,
                () -> service.hideReply(REPLY_ID, request("reason")));
        verifyNoInteractions(mapper);
    }

    @Test
    void missingCandidatePostRootOrCurrentReplyStopsAtThatLookup() {
        when(mapper.findModeratableDirectReply(REPLY_ID)).thenReturn(null);
        assertError(ErrorCode.NOT_FOUND, () -> service.hideReply(REPLY_ID, request("reason")));
        verify(mapper).findModeratableDirectReply(REPLY_ID);
        verifyNoMoreInteractions(mapper);

        reset(mapper);
        when(mapper.findModeratableDirectReply(REPLY_ID)).thenReturn(candidate());
        when(mapper.lockBlog(BLOG_ID)).thenReturn(null);
        assertError(ErrorCode.NOT_FOUND, () -> service.hideReply(REPLY_ID, request("reason")));
        verify(mapper).findModeratableDirectReply(REPLY_ID);
        verify(mapper).lockBlog(BLOG_ID);
        verifyNoMoreInteractions(mapper);

        reset(mapper);
        when(mapper.findModeratableDirectReply(REPLY_ID)).thenReturn(candidate());
        when(mapper.lockBlog(BLOG_ID)).thenReturn(BLOG_ID);
        when(mapper.lockDirectReplyRoot(ROOT_ID, BLOG_ID)).thenReturn(null);
        assertError(ErrorCode.NOT_FOUND, () -> service.hideReply(REPLY_ID, request("reason")));
        verify(mapper).findModeratableDirectReply(REPLY_ID);
        verify(mapper).lockBlog(BLOG_ID);
        verify(mapper).lockDirectReplyRoot(ROOT_ID, BLOG_ID);
        verifyNoMoreInteractions(mapper);

        reset(mapper);
        when(mapper.findModeratableDirectReply(REPLY_ID)).thenReturn(candidate());
        when(mapper.lockBlog(BLOG_ID)).thenReturn(BLOG_ID);
        when(mapper.lockDirectReplyRoot(ROOT_ID, BLOG_ID)).thenReturn(ROOT_ID);
        when(mapper.lockModeratableDirectReply(REPLY_ID, BLOG_ID, ROOT_ID)).thenReturn(null);
        assertError(ErrorCode.NOT_FOUND, () -> service.hideReply(REPLY_ID, request("reason")));
        verify(mapper).findModeratableDirectReply(REPLY_ID);
        verify(mapper).lockBlog(BLOG_ID);
        verify(mapper).lockDirectReplyRoot(ROOT_ID, BLOG_ID);
        verify(mapper).lockModeratableDirectReply(REPLY_ID, BLOG_ID, ROOT_ID);
        verifyNoMoreInteractions(mapper);
    }

    @Test
    void auditedHiddenReplyIsIdempotentAndPreservesFirstOperatorReason() {
        prepareEligibleReply(2);
        when(mapper.findDirectReplyHideAudit(REPLY_ID, BLOG_ID, ROOT_ID)).thenReturn(AUDIT_ID);

        assertDoesNotThrow(() -> service.hideReply(REPLY_ID, request("later reason")));

        InOrder order = inOrder(mapper);
        order.verify(mapper).findModeratableDirectReply(REPLY_ID);
        order.verify(mapper).lockBlog(BLOG_ID);
        order.verify(mapper).lockDirectReplyRoot(ROOT_ID, BLOG_ID);
        order.verify(mapper).lockModeratableDirectReply(REPLY_ID, BLOG_ID, ROOT_ID);
        order.verify(mapper).findDirectReplyHideAudit(REPLY_ID, BLOG_ID, ROOT_ID);
        verify(mapper, never()).hideDirectReply(anyLong(), anyLong(), anyLong());
        verify(mapper, never()).decrementCommentCount(anyLong());
        verify(mapper, never()).insertDirectReplyHideAudit(anyLong(), anyLong(), anyLong(), anyLong(), anyString());
        verifyNoMoreInteractions(mapper);
    }

    @Test
    void hiddenReplyWithoutMatchingPurposeAuditIsNotFound() {
        prepareEligibleReply(2);
        when(mapper.findDirectReplyHideAudit(REPLY_ID, BLOG_ID, ROOT_ID)).thenReturn(null);

        assertError(ErrorCode.NOT_FOUND, () -> service.hideReply(REPLY_ID, request("reason")));

        verify(mapper).findModeratableDirectReply(REPLY_ID);
        verify(mapper).lockBlog(BLOG_ID);
        verify(mapper).lockDirectReplyRoot(ROOT_ID, BLOG_ID);
        verify(mapper).lockModeratableDirectReply(REPLY_ID, BLOG_ID, ROOT_ID);
        verify(mapper).findDirectReplyHideAudit(REPLY_ID, BLOG_ID, ROOT_ID);
        verify(mapper, never()).hideDirectReply(anyLong(), anyLong(), anyLong());
        verify(mapper, never()).decrementCommentCount(anyLong());
        verify(mapper, never()).insertDirectReplyHideAudit(anyLong(), anyLong(), anyLong(), anyLong(), anyString());
        verifyNoMoreInteractions(mapper);
    }

    @Test
    void legacyModerationAndAuthorWithdrawnStatusesAreRejectedByCurrentRead() {
        for (int ineligibleStatus : new int[]{1, 3}) {
            reset(mapper);
            when(mapper.findModeratableDirectReply(REPLY_ID)).thenReturn(candidate());
            when(mapper.lockBlog(BLOG_ID)).thenReturn(BLOG_ID);
            when(mapper.lockDirectReplyRoot(ROOT_ID, BLOG_ID)).thenReturn(ROOT_ID);
            // The mapper's eligibility query excludes status 1 and 3, so the current locking read has no row.
            when(mapper.lockModeratableDirectReply(REPLY_ID, BLOG_ID, ROOT_ID)).thenReturn(null);

            assertError(ErrorCode.NOT_FOUND, () -> service.hideReply(REPLY_ID, request("reason")));

            verify(mapper).findModeratableDirectReply(REPLY_ID);
            verify(mapper).lockBlog(BLOG_ID);
            verify(mapper).lockDirectReplyRoot(ROOT_ID, BLOG_ID);
            verify(mapper).lockModeratableDirectReply(REPLY_ID, BLOG_ID, ROOT_ID);
            verify(mapper, never()).hideDirectReply(anyLong(), anyLong(), anyLong());
            verify(mapper, never()).decrementCommentCount(anyLong());
            verify(mapper, never()).insertDirectReplyHideAudit(anyLong(), anyLong(), anyLong(), anyLong(), anyString());
            verifyNoMoreInteractions(mapper);
        }
    }

    @Test
    void eachNonOneWriteResultFailsAndShortCircuitsSubsequentWrites() {
        prepareEligibleReply(0);
        when(mapper.hideDirectReply(REPLY_ID, BLOG_ID, ROOT_ID)).thenReturn(0);
        assertError(ErrorCode.OPERATION_FAILED, () -> service.hideReply(REPLY_ID, request("reason")));
        verify(mapper, never()).decrementCommentCount(anyLong());
        verify(mapper, never()).insertDirectReplyHideAudit(anyLong(), anyLong(), anyLong(), anyLong(), anyString());

        reset(mapper);
        prepareEligibleReply(0);
        when(mapper.hideDirectReply(REPLY_ID, BLOG_ID, ROOT_ID)).thenReturn(1);
        when(mapper.decrementCommentCount(BLOG_ID)).thenReturn(0);
        assertError(ErrorCode.OPERATION_FAILED, () -> service.hideReply(REPLY_ID, request("reason")));
        verify(mapper).decrementCommentCount(BLOG_ID);
        verify(mapper, never()).insertDirectReplyHideAudit(anyLong(), anyLong(), anyLong(), anyLong(), anyString());

        reset(mapper);
        prepareEligibleReply(0);
        when(mapper.hideDirectReply(REPLY_ID, BLOG_ID, ROOT_ID)).thenReturn(1);
        when(mapper.decrementCommentCount(BLOG_ID)).thenReturn(1);
        when(mapper.insertDirectReplyHideAudit(REPLY_ID, BLOG_ID, ROOT_ID, OPERATOR_ID, "reason")).thenReturn(0);
        assertError(ErrorCode.OPERATION_FAILED, () -> service.hideReply(REPLY_ID, request("reason")));
        verify(mapper).insertDirectReplyHideAudit(REPLY_ID, BLOG_ID, ROOT_ID, OPERATOR_ID, "reason");
    }

    @Test
    void everyMapperBoundarySanitizesDatabaseFailures() {
        when(mapper.findModeratableDirectReply(REPLY_ID)).thenThrow(databaseFailure("candidate lookup"));
        assertGenericInternalError();

        reset(mapper);
        when(mapper.findModeratableDirectReply(REPLY_ID)).thenReturn(candidate());
        when(mapper.lockBlog(BLOG_ID)).thenThrow(databaseFailure("post lock"));
        assertGenericInternalError();

        reset(mapper);
        when(mapper.findModeratableDirectReply(REPLY_ID)).thenReturn(candidate());
        when(mapper.lockBlog(BLOG_ID)).thenReturn(BLOG_ID);
        when(mapper.lockDirectReplyRoot(ROOT_ID, BLOG_ID)).thenThrow(databaseFailure("root lock"));
        assertGenericInternalError();

        reset(mapper);
        when(mapper.findModeratableDirectReply(REPLY_ID)).thenReturn(candidate());
        when(mapper.lockBlog(BLOG_ID)).thenReturn(BLOG_ID);
        when(mapper.lockDirectReplyRoot(ROOT_ID, BLOG_ID)).thenReturn(ROOT_ID);
        when(mapper.lockModeratableDirectReply(REPLY_ID, BLOG_ID, ROOT_ID)).thenThrow(databaseFailure("reply lock"));
        assertGenericInternalError();

        reset(mapper);
        prepareEligibleReply(2);
        when(mapper.findDirectReplyHideAudit(REPLY_ID, BLOG_ID, ROOT_ID)).thenThrow(databaseFailure("audit read"));
        assertGenericInternalError();

        reset(mapper);
        prepareEligibleReply(0);
        when(mapper.hideDirectReply(REPLY_ID, BLOG_ID, ROOT_ID)).thenThrow(databaseFailure("reply update"));
        assertGenericInternalError();

        reset(mapper);
        prepareEligibleReply(0);
        when(mapper.hideDirectReply(REPLY_ID, BLOG_ID, ROOT_ID)).thenReturn(1);
        when(mapper.decrementCommentCount(BLOG_ID)).thenThrow(databaseFailure("counter update"));
        assertGenericInternalError();

        reset(mapper);
        prepareEligibleReply(0);
        when(mapper.hideDirectReply(REPLY_ID, BLOG_ID, ROOT_ID)).thenReturn(1);
        when(mapper.decrementCommentCount(BLOG_ID)).thenReturn(1);
        when(mapper.insertDirectReplyHideAudit(REPLY_ID, BLOG_ID, ROOT_ID, OPERATOR_ID, "reason"))
                .thenThrow(databaseFailure("audit insert"));
        assertGenericInternalError();
    }

    private DataAccessResourceFailureException databaseFailure(String boundary) {
        return new DataAccessResourceFailureException("private SQL, content, and credentials at " + boundary);
    }

    private void assertGenericInternalError() {
        BusinessException error = assertThrows(BusinessException.class,
                () -> service.hideReply(REPLY_ID, request("reason")));
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
