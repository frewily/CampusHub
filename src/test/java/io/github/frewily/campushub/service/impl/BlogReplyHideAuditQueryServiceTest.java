package io.github.frewily.campushub.service.impl;

import io.github.frewily.campushub.dto.UserDTO;
import io.github.frewily.campushub.dto.response.BlogReplyHideAuditResponse;
import io.github.frewily.campushub.exception.BusinessException;
import io.github.frewily.campushub.exception.ErrorCode;
import io.github.frewily.campushub.mapper.BlogCommentsMapper;
import io.github.frewily.campushub.utils.UserHolder;
import org.apache.ibatis.annotations.Param;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

import java.io.IOException;
import java.io.InputStream;
import java.io.ByteArrayOutputStream;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

class BlogReplyHideAuditQueryServiceTest {
    private static final long REPLY_ID = 9007199254740995L;
    private static final long BLOG_ID = 9007199254740997L;
    private static final long ROOT_COMMENT_ID = 9007199254740999L;
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
    void returnsTheStoredAuditFromExactlyOneMapperQuery() {
        BlogReplyHideAuditResponse stored = audit();
        when(mapper.findDirectReplyHideAuditDetails(REPLY_ID)).thenReturn(stored);

        BlogReplyHideAuditResponse result = service.queryReplyHideAudit(REPLY_ID);

        assertEquals(stored, result);
        assertEquals(Long.toString(AUDIT_ID), result.getId());
        assertEquals(Long.toString(REPLY_ID), result.getReplyId());
        assertEquals(Long.toString(BLOG_ID), result.getBlogId());
        assertEquals(Long.toString(ROOT_COMMENT_ID), result.getRootCommentId());
        assertEquals(Long.toString(OPERATOR_ID), result.getOperatorUserId());
        assertEquals("policy violation", result.getReason());
        assertEquals(0, result.getFromStatus());
        assertEquals(2, result.getToStatus());
        assertEquals(CREATED, result.getCreateTime());
        verify(mapper).findDirectReplyHideAuditDetails(REPLY_ID);
        verifyNoMoreInteractions(mapper);
    }

    @Test
    void invalidReplyIdsAndMissingOrInvalidCurrentIdentityCannotReachMapper() {
        for (Long id : new Long[]{null, 0L, -1L}) {
            assertError(ErrorCode.VALIDATION_FAILED, () -> service.queryReplyHideAudit(id));
        }

        UserHolder.removeUser();
        assertError(ErrorCode.AUTHENTICATION_FAILED, () -> service.queryReplyHideAudit(REPLY_ID));
        for (Long id : new Long[]{null, 0L, -OPERATOR_ID}) {
            UserDTO invalid = new UserDTO();
            invalid.setId(id);
            UserHolder.saveUser(invalid);
            assertError(ErrorCode.AUTHENTICATION_FAILED, () -> service.queryReplyHideAudit(REPLY_ID));
        }
        verifyNoInteractions(mapper);
    }

    @Test
    void missingAuditIsNotFoundAfterOnlyTheAuditLookup() {
        when(mapper.findDirectReplyHideAuditDetails(REPLY_ID)).thenReturn(null);

        assertError(ErrorCode.NOT_FOUND, () -> service.queryReplyHideAudit(REPLY_ID));

        verify(mapper).findDirectReplyHideAuditDetails(REPLY_ID);
        verifyNoMoreInteractions(mapper);
    }

    @Test
    void mapperDataFailureBecomesGenericInternalErrorWithoutCauseOrSensitiveText() {
        when(mapper.findDirectReplyHideAuditDetails(REPLY_ID)).thenThrow(
                new DataAccessResourceFailureException("private audit SQL and connection data"));

        BusinessException error = assertThrows(BusinessException.class, () -> service.queryReplyHideAudit(REPLY_ID));

        assertEquals(ErrorCode.INTERNAL_ERROR, error.getErrorCode());
        assertNull(error.getCause());
        assertFalse(error.getMessage().contains("private"));
        verify(mapper).findDirectReplyHideAuditDetails(REPLY_ID);
        verifyNoMoreInteractions(mapper);
    }

    @Test
    void queryDeclaresReadOnlyRepeatableReadAndMapperXmlIsOneBoundOrdinarySelect() throws Exception {
        Method query = BlogCommentsServiceImpl.class.getMethod("queryReplyHideAudit", Long.class);
        Transactional transaction = query.getAnnotation(Transactional.class);
        assertTrue(transaction.readOnly());
        assertEquals(Isolation.REPEATABLE_READ, transaction.isolation());

        Method mapperMethod = BlogCommentsMapper.class.getMethod("findDirectReplyHideAuditDetails", Long.class);
        Param parameter = mapperMethod.getParameters()[0].getAnnotation(Param.class);
        assertTrue(parameter != null);
        assertEquals("replyId", parameter.value());

        String mapperXml = readMapperXml();
        int start = mapperXml.indexOf("<select id=\"findDirectReplyHideAuditDetails\"");
        assertTrue(start >= 0, "dedicated reply audit query must exist");
        int end = mapperXml.indexOf("</select>", start);
        assertTrue(end > start, "dedicated mapper select must be closed");
        String queryXml = mapperXml.substring(start, end);
        assertTrue(queryXml.contains("timeout=\"5\""));
        assertTrue(queryXml.contains("#{replyId}"));
        assertTrue(queryXml.contains("FROM tb_blog_reply_hide_audit WHERE reply_id = #{replyId}"));
        assertTrue(queryXml.contains("id, reply_id, blog_id, root_comment_id, operator_user_id, reason, from_status, to_status, create_time"));
        String normalized = queryXml.toUpperCase(java.util.Locale.ROOT);
        assertFalse(normalized.contains(" JOIN "));
        assertFalse(normalized.contains("FOR UPDATE"));
    }

    private String readMapperXml() throws IOException {
        try (InputStream input = getClass().getResourceAsStream("/mapper/BlogCommentsMapper.xml")) {
            assertTrue(input != null, "BlogCommentsMapper.xml must be available on the test classpath");
            ByteArrayOutputStream output = new ByteArrayOutputStream();
            byte[] buffer = new byte[4096];
            int read;
            while ((read = input.read(buffer)) != -1) {
                output.write(buffer, 0, read);
            }
            return new String(output.toByteArray(), StandardCharsets.UTF_8);
        }
    }

    private BlogReplyHideAuditResponse audit() {
        return new BlogReplyHideAuditResponse(Long.toString(AUDIT_ID), Long.toString(REPLY_ID),
                Long.toString(BLOG_ID), Long.toString(ROOT_COMMENT_ID), Long.toString(OPERATOR_ID),
                "policy violation", 0, 2, CREATED);
    }

    private void assertError(ErrorCode expected, org.junit.jupiter.api.function.Executable action) {
        BusinessException error = assertThrows(BusinessException.class, action);
        assertEquals(expected, error.getErrorCode());
    }
}
