package io.github.frewily.campushub.service.impl;

import io.github.frewily.campushub.dto.UserDTO;
import io.github.frewily.campushub.dto.request.BlogCommentHideAuditPageRequest;
import io.github.frewily.campushub.dto.response.BlogReplyHideAuditPage;
import io.github.frewily.campushub.dto.response.BlogReplyHideAuditResponse;
import io.github.frewily.campushub.exception.BusinessException;
import io.github.frewily.campushub.exception.ErrorCode;
import io.github.frewily.campushub.mapper.BlogCommentsMapper;
import io.github.frewily.campushub.utils.UserHolder;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

import javax.validation.Validation;
import javax.validation.ValidatorFactory;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class BlogReplyHideAuditPageServiceTest {
    private static final long BLOG_ID = 9007199254740995L;
    private static final long OPERATOR_ID = 9007199254740997L;
    private static final LocalDateTime CREATED = LocalDateTime.of(2026, 10, 11, 12, 34, 56);

    private BlogCommentsMapper mapper;
    private BlogCommentsServiceImpl service;
    private ValidatorFactory validatorFactory;

    @BeforeEach
    void setUp() {
        mapper = mock(BlogCommentsMapper.class);
        validatorFactory = Validation.buildDefaultValidatorFactory();
        service = new BlogCommentsServiceImpl(validatorFactory.getValidator());
        ReflectionTestUtils.setField(service, "baseMapper", mapper);
        UserDTO operator = new UserDTO();
        operator.setId(OPERATOR_ID);
        UserHolder.saveUser(operator);
    }

    @AfterEach
    void clearContext() {
        try {
            UserHolder.removeUser();
        } finally {
            validatorFactory.close();
        }
    }

    @Test
    void absentBlogOrAuditRowsProduceAnEmptyPageWithoutEntityLookups() {
        // A nonexistent blog has no audit rows; the audit query itself defines the empty result.
        when(mapper.findReplyHideAuditsByBlog(BLOG_ID, null, 21))
                .thenReturn(Collections.<BlogReplyHideAuditResponse>emptyList());

        BlogReplyHideAuditPage result = service.listReplyHideAudits(BLOG_ID, new BlogCommentHideAuditPageRequest());

        assertTrue(result.getItems().isEmpty());
        assertFalse(result.isHasNext());
        assertNull(result.getNextBeforeId());
        verify(mapper).findReplyHideAuditsByBlog(BLOG_ID, null, 21);
        verifyNoMoreInteractions(mapper);
    }

    @Test
    void shortExactAndLookaheadResultsHaveCorrectCursorAndNeverReturnLookahead() {
        BlogCommentHideAuditPageRequest shortRequest = request(3, null);
        BlogCommentHideAuditPageRequest exactRequest = request(2, null);
        BlogCommentHideAuditPageRequest lookaheadRequest = request(2, 9007199254741010L);
        when(mapper.findReplyHideAuditsByBlog(BLOG_ID, null, 4))
                .thenReturn(Arrays.asList(audit(9007199254741008L), audit(9007199254741007L)));
        when(mapper.findReplyHideAuditsByBlog(BLOG_ID, null, 3))
                .thenReturn(Arrays.asList(audit(9007199254741006L), audit(9007199254741005L)));
        when(mapper.findReplyHideAuditsByBlog(BLOG_ID, 9007199254741010L, 3))
                .thenReturn(Arrays.asList(audit(9007199254741004L), audit(9007199254741003L),
                        audit(9007199254741002L)));

        BlogReplyHideAuditPage shortPage = service.listReplyHideAudits(BLOG_ID, shortRequest);
        BlogReplyHideAuditPage exactPage = service.listReplyHideAudits(BLOG_ID, exactRequest);
        BlogReplyHideAuditPage lookaheadPage = service.listReplyHideAudits(BLOG_ID, lookaheadRequest);

        assertEquals(2, shortPage.getItems().size());
        assertFalse(shortPage.isHasNext());
        assertNull(shortPage.getNextBeforeId());
        assertEquals(2, exactPage.getItems().size());
        assertFalse(exactPage.isHasNext());
        assertNull(exactPage.getNextBeforeId());
        assertEquals(2, lookaheadPage.getItems().size());
        assertTrue(lookaheadPage.isHasNext());
        assertEquals("9007199254741003", lookaheadPage.getNextBeforeId());
        assertEquals("9007199254741004", lookaheadPage.getItems().get(0).getId());
        assertEquals("9007199254741003", lookaheadPage.getItems().get(1).getId());
        assertFalse(lookaheadPage.getItems().stream().anyMatch(item -> "9007199254741002".equals(item.getId())));
        verify(mapper).findReplyHideAuditsByBlog(BLOG_ID, null, 4);
        verify(mapper).findReplyHideAuditsByBlog(BLOG_ID, null, 3);
        verify(mapper).findReplyHideAuditsByBlog(BLOG_ID, 9007199254741010L, 3);
        verifyNoMoreInteractions(mapper);
    }

    @Test
    void defaultAndMaximumSizesRequestOneLookaheadRowAndPassCursorAsLong() {
        BlogCommentHideAuditPageRequest defaultRequest = new BlogCommentHideAuditPageRequest();
        BlogCommentHideAuditPageRequest maxRequest = request(50, 9007199254740993L);
        when(mapper.findReplyHideAuditsByBlog(BLOG_ID, null, 21))
                .thenReturn(Collections.<BlogReplyHideAuditResponse>emptyList());
        when(mapper.findReplyHideAuditsByBlog(BLOG_ID, 9007199254740993L, 51))
                .thenReturn(Collections.<BlogReplyHideAuditResponse>emptyList());

        service.listReplyHideAudits(BLOG_ID, defaultRequest);
        service.listReplyHideAudits(BLOG_ID, maxRequest);

        verify(mapper).findReplyHideAuditsByBlog(BLOG_ID, null, 21);
        verify(mapper).findReplyHideAuditsByBlog(BLOG_ID, 9007199254740993L, 51);
        verifyNoMoreInteractions(mapper);
    }

    @Test
    void invalidBlogRequestAndCursorAreRejectedBeforeMapperAccess() {
        BlogCommentHideAuditPageRequest valid = new BlogCommentHideAuditPageRequest();
        for (Long blogId : new Long[]{null, 0L, -1L}) {
            assertError(ErrorCode.VALIDATION_FAILED, () -> service.listReplyHideAudits(blogId, valid));
        }
        for (BlogCommentHideAuditPageRequest invalid : new BlogCommentHideAuditPageRequest[]{null,
                request(null, null), request(0, null), request(51, null), request(20, 0L), request(20, -1L)}) {
            assertError(ErrorCode.VALIDATION_FAILED, () -> service.listReplyHideAudits(BLOG_ID, invalid));
        }
        verifyNoInteractions(mapper);
    }

    @Test
    void absentOrNonpositiveIdentityIsRejectedBeforeMapperAccess() {
        UserHolder.removeUser();
        assertError(ErrorCode.AUTHENTICATION_FAILED,
                () -> service.listReplyHideAudits(BLOG_ID, new BlogCommentHideAuditPageRequest()));
        for (Long userId : new Long[]{null, 0L, -OPERATOR_ID}) {
            UserDTO user = new UserDTO();
            user.setId(userId);
            UserHolder.saveUser(user);
            assertError(ErrorCode.AUTHENTICATION_FAILED,
                    () -> service.listReplyHideAudits(BLOG_ID, new BlogCommentHideAuditPageRequest()));
        }
        verifyNoInteractions(mapper);
    }

    @Test
    void mapperFailureIsSanitizedAndNoOtherMapperOperationIsInvoked() {
        when(mapper.findReplyHideAuditsByBlog(BLOG_ID, null, 21)).thenThrow(
                new DataAccessResourceFailureException("private SQL, connection, and row data"));

        BusinessException error = assertThrows(BusinessException.class, () ->
                service.listReplyHideAudits(BLOG_ID, new BlogCommentHideAuditPageRequest()));

        assertEquals(ErrorCode.INTERNAL_ERROR, error.getErrorCode());
        assertNull(error.getCause());
        assertFalse(error.getMessage().contains("private"));
        verify(mapper).findReplyHideAuditsByBlog(BLOG_ID, null, 21);
        verifyNoMoreInteractions(mapper);
    }

    @Test
    void serviceAndMapperKeepThePageQueryReadOnlyAndBounded() throws Exception {
        Transactional transaction = BlogCommentsServiceImpl.class
                .getMethod("listReplyHideAudits", Long.class, BlogCommentHideAuditPageRequest.class)
                .getAnnotation(Transactional.class);
        assertNotNull(transaction);
        assertTrue(transaction.readOnly());
        assertEquals(Isolation.REPEATABLE_READ, transaction.isolation());

        String xml = new String(Files.readAllBytes(Paths.get("src/main/resources/mapper/BlogCommentsMapper.xml")),
                StandardCharsets.UTF_8);
        String marker = "<select id=\"findReplyHideAuditsByBlog\"";
        int start = xml.indexOf(marker);
        assertTrue(start >= 0, "reply audit page mapper statement must exist");
        int end = xml.indexOf("</select>", start);
        assertTrue(end > start, "reply audit page mapper statement must close");
        String statement = xml.substring(start, end);
        String normalized = statement.toUpperCase(java.util.Locale.ROOT);
        assertTrue(statement.contains("timeout=\"5\""));
        assertTrue(statement.contains("#{blogId}"));
        assertTrue(statement.contains("#{beforeId}"));
        assertTrue(statement.contains("#{limit}"));
        assertTrue(statement.contains("FROM tb_blog_reply_hide_audit"));
        assertTrue(normalized.contains("ORDER BY ID DESC"));
        assertFalse(normalized.contains("JOIN"));
        assertFalse(normalized.contains("COUNT"));
        assertFalse(normalized.contains("FOR UPDATE"));
    }

    private BlogCommentHideAuditPageRequest request(Integer size, Long beforeId) {
        BlogCommentHideAuditPageRequest request = new BlogCommentHideAuditPageRequest();
        request.setSize(size);
        request.setBeforeId(beforeId);
        return request;
    }

    private BlogReplyHideAuditResponse audit(long id) {
        return new BlogReplyHideAuditResponse(Long.toString(id), "9007199254740993", Long.toString(BLOG_ID),
                "9007199254740991", Long.toString(OPERATOR_ID), "policy violation", 0, 2, CREATED);
    }

    private void assertError(ErrorCode expected, org.junit.jupiter.api.function.Executable action) {
        BusinessException error = assertThrows(BusinessException.class, action);
        assertEquals(expected, error.getErrorCode());
    }
}
