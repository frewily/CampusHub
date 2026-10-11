package io.github.frewily.campushub.service.impl;

import io.github.frewily.campushub.dto.UserDTO;
import io.github.frewily.campushub.dto.response.BlogCommentHideAuditResponse;
import io.github.frewily.campushub.exception.BusinessException;
import io.github.frewily.campushub.exception.ErrorCode;
import io.github.frewily.campushub.mapper.BlogCommentsMapper;
import io.github.frewily.campushub.utils.UserHolder;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.LocalDateTime;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class BlogCommentHideAuditQueryServiceTest {
    private static final long COMMENT_ID = 9007199254740995L;
    private static final long BLOG_ID = 9007199254740997L;
    private static final long OPERATOR_ID = 9007199254740993L;
    private static final long AUDIT_ID = 9007199254741001L;
    private static final LocalDateTime CREATED = LocalDateTime.of(2026, 10, 11, 12, 34, 56);

    private BlogCommentsMapper mapper;
    private BlogCommentsServiceImpl service;

    @BeforeEach
    void setUp() {
        mapper = mock(BlogCommentsMapper.class);
        service = new BlogCommentsServiceImpl(null);
        ReflectionTestUtils.setField(service, "baseMapper", mapper);
        UserDTO operator = new UserDTO();
        operator.setId(OPERATOR_ID);
        UserHolder.saveUser(operator);
    }

    @AfterEach
    void clearUser() {
        UserHolder.removeUser();
    }

    @Test
    void returnsStoredAuditMetadataFromSingleMapperQuery() {
        BlogCommentHideAuditResponse stored = audit();
        when(mapper.findFirstLevelHideAuditDetails(COMMENT_ID)).thenReturn(stored);

        BlogCommentHideAuditResponse result = service.queryHideAudit(COMMENT_ID);

        assertEquals(stored, result);
        assertEquals(Long.toString(AUDIT_ID), result.getId());
        assertEquals(Long.toString(COMMENT_ID), result.getCommentId());
        assertEquals(Long.toString(BLOG_ID), result.getBlogId());
        assertEquals(Long.toString(OPERATOR_ID), result.getOperatorUserId());
        assertEquals("policy violation", result.getReason());
        assertEquals(0, result.getFromStatus());
        assertEquals(2, result.getToStatus());
        assertEquals(CREATED, result.getCreateTime());
        verify(mapper).findFirstLevelHideAuditDetails(COMMENT_ID);
        verifyNoMoreInteractions(mapper);
    }

    @Test
    void invalidCommentIdOrMissingOrInvalidCurrentUserCannotReachMapper() {
        for (Long id : new Long[]{null, 0L, -1L}) {
            assertError(ErrorCode.VALIDATION_FAILED, () -> service.queryHideAudit(id));
        }

        UserHolder.removeUser();
        assertError(ErrorCode.AUTHENTICATION_FAILED, () -> service.queryHideAudit(COMMENT_ID));
        for (Long id : new Long[]{null, 0L, -OPERATOR_ID}) {
            UserDTO invalid = new UserDTO();
            invalid.setId(id);
            UserHolder.saveUser(invalid);
            assertError(ErrorCode.AUTHENTICATION_FAILED, () -> service.queryHideAudit(COMMENT_ID));
        }
        verifyNoInteractions(mapper);
    }

    @Test
    void absentAuditIsNotFoundWithoutConsultingCurrentCommentBlogOrActor() {
        when(mapper.findFirstLevelHideAuditDetails(COMMENT_ID)).thenReturn(null);

        assertError(ErrorCode.NOT_FOUND, () -> service.queryHideAudit(COMMENT_ID));

        verify(mapper).findFirstLevelHideAuditDetails(COMMENT_ID);
        verifyNoMoreInteractions(mapper);
    }

    @Test
    void mapperFailureBecomesSanitizedInternalErrorWithoutCause() {
        when(mapper.findFirstLevelHideAuditDetails(COMMENT_ID)).thenThrow(
                new DataAccessResourceFailureException("private audit SQL and connection data"));

        BusinessException error = assertThrows(BusinessException.class, () -> service.queryHideAudit(COMMENT_ID));

        assertEquals(ErrorCode.INTERNAL_ERROR, error.getErrorCode());
        assertNull(error.getCause());
        assertFalse(error.getMessage().contains("private"));
        verify(mapper).findFirstLevelHideAuditDetails(COMMENT_ID);
        verifyNoMoreInteractions(mapper);
    }

    private BlogCommentHideAuditResponse audit() {
        return new BlogCommentHideAuditResponse(Long.toString(AUDIT_ID), Long.toString(COMMENT_ID),
                Long.toString(BLOG_ID), Long.toString(OPERATOR_ID), "policy violation", 0, 2, CREATED);
    }

    private void assertError(ErrorCode expected, org.junit.jupiter.api.function.Executable action) {
        BusinessException error = assertThrows(BusinessException.class, action);
        assertEquals(expected, error.getErrorCode());
    }
}
