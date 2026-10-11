package io.github.frewily.campushub.service.impl;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.frewily.campushub.dto.UserDTO;
import io.github.frewily.campushub.dto.request.BlogCommentHideAuditPageRequest;
import io.github.frewily.campushub.dto.response.BlogCommentHideAuditPage;
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

import javax.validation.Validation;
import javax.validation.ValidatorFactory;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class BlogCommentHideAuditPageServiceTest {
    private static final long BLOG_ID = 9007199254740997L;
    private static final long OPERATOR_ID = 9007199254740993L;
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
        UserHolder.removeUser();
        validatorFactory.close();
    }

    @Test
    void defaultSizeUsesRealBeanValidationAndRequestsOneLookaheadRow() {
        BlogCommentHideAuditPageRequest request = new BlogCommentHideAuditPageRequest();
        assertEquals(Integer.valueOf(20), request.getSize());
        when(mapper.findHideAuditsByBlog(BLOG_ID, null, 21)).thenReturn(Collections.<BlogCommentHideAuditResponse>emptyList());

        BlogCommentHideAuditPage result = service.listHideAudits(BLOG_ID, request);

        assertTrue(result.getItems().isEmpty());
        assertFalse(result.isHasNext());
        assertNull(result.getNextBeforeId());
        verify(mapper).findHideAuditsByBlog(BLOG_ID, null, 21);
        verifyNoMoreInteractions(mapper);
    }

    @Test
    void maximumSizeRequestsFiftyOneAndCursorRemainsExactLongValue() throws Exception {
        BlogCommentHideAuditPageRequest request = new BlogCommentHideAuditPageRequest();
        request.setSize(50);
        request.setBeforeId(9007199254740993L);
        when(mapper.findHideAuditsByBlog(BLOG_ID, 9007199254740993L, 51))
                .thenReturn(Collections.<BlogCommentHideAuditResponse>emptyList());

        BlogCommentHideAuditPage result = service.listHideAudits(BLOG_ID, request);
        BlogCommentHideAuditPageRequest roundTrip = new ObjectMapper().readValue(
                "{\"size\":50,\"beforeId\":9007199254740993,\"ignored\":true}",
                BlogCommentHideAuditPageRequest.class);

        assertFalse(result.isHasNext());
        assertEquals(Long.valueOf(9007199254740993L), roundTrip.getBeforeId());
        assertEquals(Integer.valueOf(50), roundTrip.getSize());
        verify(mapper).findHideAuditsByBlog(BLOG_ID, 9007199254740993L, 51);
        verifyNoMoreInteractions(mapper);
    }

    @Test
    void trimsLookaheadAndUsesLastReturnedAuditIdAsNextCursor() {
        List<BlogCommentHideAuditResponse> rows = new ArrayList<>();
        rows.add(audit(9007199254741004L));
        rows.add(audit(9007199254741003L));
        rows.add(audit(9007199254741002L));
        BlogCommentHideAuditPageRequest request = new BlogCommentHideAuditPageRequest();
        request.setSize(2);
        when(mapper.findHideAuditsByBlog(BLOG_ID, null, 3)).thenReturn(rows);

        BlogCommentHideAuditPage result = service.listHideAudits(BLOG_ID, request);

        assertEquals(2, result.getItems().size());
        assertTrue(result.isHasNext());
        assertEquals("9007199254741003", result.getNextBeforeId());
        assertEquals("9007199254741004", result.getItems().get(0).getId());
        assertEquals("9007199254741003", result.getItems().get(1).getId());
        verify(mapper).findHideAuditsByBlog(BLOG_ID, null, 3);
        verifyNoMoreInteractions(mapper);
    }

    @Test
    void exactSizeAndEmptyResultsHaveNoCursor() {
        BlogCommentHideAuditPageRequest request = new BlogCommentHideAuditPageRequest();
        request.setSize(2);
        when(mapper.findHideAuditsByBlog(BLOG_ID, null, 3))
                .thenReturn(java.util.Arrays.asList(audit(91L), audit(90L)));

        BlogCommentHideAuditPage result = service.listHideAudits(BLOG_ID, request);

        assertEquals(2, result.getItems().size());
        assertFalse(result.isHasNext());
        assertNull(result.getNextBeforeId());
        verify(mapper).findHideAuditsByBlog(BLOG_ID, null, 3);
        verifyNoMoreInteractions(mapper);
    }

    @Test
    void invalidBlogOrRequestIsRejectedBeforeMapperAccess() {
        BlogCommentHideAuditPageRequest valid = new BlogCommentHideAuditPageRequest();
        for (Long blogId : new Long[]{null, 0L, -1L}) {
            assertError(ErrorCode.VALIDATION_FAILED, () -> service.listHideAudits(blogId, valid));
        }
        for (BlogCommentHideAuditPageRequest invalid : new BlogCommentHideAuditPageRequest[]{null,
                request(null, null), request(0, null), request(51, null), request(20, 0L), request(20, -1L)}) {
            assertError(ErrorCode.VALIDATION_FAILED, () -> service.listHideAudits(BLOG_ID, invalid));
        }
        verifyNoInteractions(mapper);
    }

    @Test
    void absentOrInvalidCurrentIdentityIsRejectedWithoutAnyOtherLookup() {
        UserHolder.removeUser();
        assertError(ErrorCode.AUTHENTICATION_FAILED,
                () -> service.listHideAudits(BLOG_ID, new BlogCommentHideAuditPageRequest()));
        for (Long userId : new Long[]{null, 0L, -OPERATOR_ID}) {
            UserDTO invalid = new UserDTO();
            invalid.setId(userId);
            UserHolder.saveUser(invalid);
            assertError(ErrorCode.AUTHENTICATION_FAILED,
                    () -> service.listHideAudits(BLOG_ID, new BlogCommentHideAuditPageRequest()));
        }
        verifyNoInteractions(mapper);
    }

    @Test
    void mapperFailureIsSanitizedAndCarriesNoDatabaseCause() {
        when(mapper.findHideAuditsByBlog(BLOG_ID, null, 21)).thenThrow(
                new DataAccessResourceFailureException("private SQL, connection, and row data"));

        BusinessException error = assertThrows(BusinessException.class, () ->
                service.listHideAudits(BLOG_ID, new BlogCommentHideAuditPageRequest()));

        assertEquals(ErrorCode.INTERNAL_ERROR, error.getErrorCode());
        assertNull(error.getCause());
        assertFalse(error.getMessage().contains("private"));
        verify(mapper).findHideAuditsByBlog(BLOG_ID, null, 21);
        verifyNoMoreInteractions(mapper);
    }

    private BlogCommentHideAuditPageRequest request(Integer size, Long beforeId) {
        BlogCommentHideAuditPageRequest request = new BlogCommentHideAuditPageRequest();
        request.setSize(size);
        request.setBeforeId(beforeId);
        return request;
    }

    private BlogCommentHideAuditResponse audit(long id) {
        return new BlogCommentHideAuditResponse(Long.toString(id), "9007199254740995",
                Long.toString(BLOG_ID), Long.toString(OPERATOR_ID), "policy violation", 0, 2, CREATED);
    }

    private void assertError(ErrorCode expected, org.junit.jupiter.api.function.Executable action) {
        BusinessException error = assertThrows(BusinessException.class, action);
        assertEquals(expected, error.getErrorCode());
    }
}
