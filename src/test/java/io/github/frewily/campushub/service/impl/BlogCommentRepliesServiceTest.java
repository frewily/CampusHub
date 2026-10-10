package io.github.frewily.campushub.service.impl;

import io.github.frewily.campushub.dto.UserDTO;
import io.github.frewily.campushub.dto.request.BlogCommentPageRequest;
import io.github.frewily.campushub.dto.request.BlogCommentReplyRequest;
import io.github.frewily.campushub.dto.response.BlogCommentItem;
import io.github.frewily.campushub.dto.response.BlogCommentPage;
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
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.http.HttpStatus;
import org.springframework.test.util.ReflectionTestUtils;

import javax.validation.Validation;
import javax.validation.Validator;
import javax.validation.ValidatorFactory;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class BlogCommentRepliesServiceTest {
    private static ValidatorFactory factory;
    private BlogCommentsMapper mapper;
    private BlogCommentsServiceImpl service;

    private static final long ROOT_ID = 71L;
    private static final long BLOG_ID = 17L;
    private static final long AUTHOR_ID = 9007199254740993L;

    @BeforeAll
    static void validation() {
        factory = Validation.buildDefaultValidatorFactory();
    }

    @AfterAll
    static void close() {
        factory.close();
    }

    @BeforeEach
    void setup() {
        Validator validator = factory.getValidator();
        mapper = mock(BlogCommentsMapper.class);
        service = new BlogCommentsServiceImpl(validator);
        ReflectionTestUtils.setField(service, "baseMapper", mapper);
        UserDTO user = new UserDTO();
        user.setId(AUTHOR_ID);
        UserHolder.saveUser(user);
    }

    @AfterEach
    void clearUser() {
        UserHolder.removeUser();
    }

    private BlogCommentReplyRequest reply(String content) {
        BlogCommentReplyRequest request = new BlogCommentReplyRequest();
        request.setContent(content);
        return request;
    }

    private BlogComments root() {
        return new BlogComments().setId(ROOT_ID).setBlogId(BLOG_ID).setParentId(0L)
                .setAnswerId(0L).setStatus(0);
    }

    private BlogComments row(long id, String content) {
        return new BlogComments().setId(id).setBlogId(BLOG_ID).setUserId(AUTHOR_ID)
                .setContent(content).setCreateTime(LocalDateTime.of(2026, 1, 1, 0, 0));
    }

    private void prepareCreate() {
        when(mapper.findVisibleRoot(ROOT_ID)).thenReturn(root());
        when(mapper.lockBlog(BLOG_ID)).thenReturn(BLOG_ID);
        when(mapper.lockVisibleRoot(ROOT_ID, BLOG_ID)).thenReturn(root());
        when(mapper.insert(any(BlogComments.class))).thenAnswer(call -> {
            BlogComments inserted = call.getArgument(0);
            inserted.setId(810L);
            return 1;
        });
        when(mapper.incrementCommentCount(BLOG_ID)).thenReturn(1);
        when(mapper.selectById(810L)).thenReturn(row(810L, "stored reply"));
    }

    private void assertError(ErrorCode code, org.junit.jupiter.api.function.Executable action) {
        BusinessException error = assertThrows(BusinessException.class, action);
        assertEquals(code, error.getErrorCode());
        if (code == ErrorCode.OPERATION_FAILED) {
            assertEquals(HttpStatus.UNPROCESSABLE_ENTITY, error.getErrorCode().getHttpStatus());
        }
    }

    @Test
    void createReplyUsesAuthenticatedAuthorAndServerControlledFieldsInLockOrder() {
        prepareCreate();

        BlogCommentItem item = service.createReply(ROOT_ID, reply("reply body"));

        assertEquals("810", item.getId());
        assertEquals(String.valueOf(BLOG_ID), item.getBlogId());
        assertEquals(String.valueOf(AUTHOR_ID), item.getUserId());
        org.mockito.InOrder order = inOrder(mapper);
        order.verify(mapper).findVisibleRoot(ROOT_ID);
        order.verify(mapper).lockBlog(BLOG_ID);
        order.verify(mapper).lockVisibleRoot(ROOT_ID, BLOG_ID);
        order.verify(mapper).insert(any(BlogComments.class));
        order.verify(mapper).incrementCommentCount(BLOG_ID);
        order.verify(mapper).selectById(810L);
        verifyNoMoreInteractions(mapper);
    }

    @Test
    void replyInsertContainsOnlyAllowedClientAndServerValues() {
        prepareCreate();
        when(mapper.insert(any(BlogComments.class))).thenAnswer(call -> {
            BlogComments inserted = call.getArgument(0);
            assertNull(inserted.getId());
            assertEquals(AUTHOR_ID, inserted.getUserId());
            assertEquals(BLOG_ID, inserted.getBlogId());
            assertEquals(ROOT_ID, inserted.getParentId());
            assertEquals(ROOT_ID, inserted.getAnswerId());
            assertEquals("client text", inserted.getContent());
            assertEquals(0, inserted.getLiked());
            assertEquals(0, inserted.getStatus());
            assertNull(inserted.getCreateTime());
            assertNull(inserted.getUpdateTime());
            inserted.setId(810L);
            return 1;
        });

        service.createReply(ROOT_ID, reply("client text"));
    }

    @Test
    void missingOrInvalidAuthorAndInvalidReplyCannotReachDatabase() {
        UserHolder.removeUser();
        assertError(ErrorCode.AUTHENTICATION_FAILED, () -> service.createReply(ROOT_ID, reply("hello")));
        UserDTO invalidAuthor = new UserDTO();
        invalidAuthor.setId(0L);
        UserHolder.saveUser(invalidAuthor);
        assertError(ErrorCode.AUTHENTICATION_FAILED, () -> service.createReply(ROOT_ID, reply("hello")));

        UserDTO validAuthor = new UserDTO();
        validAuthor.setId(AUTHOR_ID);
        UserHolder.saveUser(validAuthor);
        List<BlogCommentReplyRequest> invalid = new ArrayList<>();
        invalid.add(null);
        invalid.add(reply("  \n"));
        invalid.add(reply(String.join("", Collections.nCopies(256, "x"))));
        for (BlogCommentReplyRequest request : invalid) {
            assertError(ErrorCode.VALIDATION_FAILED, () -> service.createReply(ROOT_ID, request));
        }
        assertError(ErrorCode.VALIDATION_FAILED, () -> service.createReply(null, reply("hello")));
        assertError(ErrorCode.VALIDATION_FAILED, () -> service.createReply(0L, reply("hello")));
        assertError(ErrorCode.VALIDATION_FAILED, () -> service.createReply(-1L, reply("hello")));
        verifyNoInteractions(mapper);
    }

    @Test
    void missingRootAndMissingBlogReturnNotFoundWithoutWriting() {
        when(mapper.findVisibleRoot(ROOT_ID)).thenReturn(null);
        assertError(ErrorCode.NOT_FOUND, () -> service.createReply(ROOT_ID, reply("hello")));
        verify(mapper).findVisibleRoot(ROOT_ID);
        verifyNoMoreInteractions(mapper);

        reset(mapper);
        when(mapper.findVisibleRoot(ROOT_ID)).thenReturn(root());
        when(mapper.lockBlog(BLOG_ID)).thenReturn(null);
        assertError(ErrorCode.NOT_FOUND, () -> service.createReply(ROOT_ID, reply("hello")));
        verify(mapper).findVisibleRoot(ROOT_ID);
        verify(mapper).lockBlog(BLOG_ID);
        verifyNoMoreInteractions(mapper);
    }

    @Test
    void changedOrHiddenLockedRootReturnsNotFoundBeforeInsert() {
        when(mapper.findVisibleRoot(ROOT_ID)).thenReturn(root());
        when(mapper.lockBlog(BLOG_ID)).thenReturn(BLOG_ID);
        when(mapper.lockVisibleRoot(ROOT_ID, BLOG_ID)).thenReturn(null);
        assertError(ErrorCode.NOT_FOUND, () -> service.createReply(ROOT_ID, reply("hello")));
        verify(mapper).findVisibleRoot(ROOT_ID);
        verify(mapper).lockBlog(BLOG_ID);
        verify(mapper).lockVisibleRoot(ROOT_ID, BLOG_ID);
        verify(mapper, never()).insert(any(BlogComments.class));

        // SQL returns only id/blog_id and filters hidden/non-root rows to null.
        // Actual visibility changes and predicate enforcement are covered by BlogCommentsMySqlIT.
    }

    @Test
    void failedInsertCounterOrReadBackIsAnOperationFailure() {
        when(mapper.findVisibleRoot(ROOT_ID)).thenReturn(root());
        when(mapper.lockBlog(BLOG_ID)).thenReturn(BLOG_ID);
        when(mapper.lockVisibleRoot(ROOT_ID, BLOG_ID)).thenReturn(root());
        when(mapper.insert(any(BlogComments.class))).thenReturn(0);
        assertError(ErrorCode.OPERATION_FAILED, () -> service.createReply(ROOT_ID, reply("hello")));
        verify(mapper, never()).incrementCommentCount(anyLong());

        reset(mapper);
        when(mapper.findVisibleRoot(ROOT_ID)).thenReturn(root());
        when(mapper.lockBlog(BLOG_ID)).thenReturn(BLOG_ID);
        when(mapper.lockVisibleRoot(ROOT_ID, BLOG_ID)).thenReturn(root());
        when(mapper.insert(any(BlogComments.class))).thenAnswer(call -> {
            ((BlogComments) call.getArgument(0)).setId(810L);
            return 1;
        });
        when(mapper.incrementCommentCount(BLOG_ID)).thenReturn(0);
        assertError(ErrorCode.OPERATION_FAILED, () -> service.createReply(ROOT_ID, reply("hello")));
        verify(mapper, never()).selectById(anyLong());

        reset(mapper);
        when(mapper.findVisibleRoot(ROOT_ID)).thenReturn(root());
        when(mapper.lockBlog(BLOG_ID)).thenReturn(BLOG_ID);
        when(mapper.lockVisibleRoot(ROOT_ID, BLOG_ID)).thenReturn(root());
        when(mapper.insert(any(BlogComments.class))).thenAnswer(call -> {
            ((BlogComments) call.getArgument(0)).setId(810L);
            return 1;
        });
        when(mapper.incrementCommentCount(BLOG_ID)).thenReturn(1);
        when(mapper.selectById(810L)).thenReturn(null);
        assertError(ErrorCode.OPERATION_FAILED, () -> service.createReply(ROOT_ID, reply("hello")));
    }

    @Test
    void createAndReadDatabaseErrorsAreGenericAndDoNotExposeDriverCause() {
        when(mapper.findVisibleRoot(ROOT_ID)).thenThrow(
                new DataAccessResourceFailureException("private SQL/credential"));
        BusinessException createError = assertThrows(BusinessException.class,
                () -> service.createReply(ROOT_ID, reply("hello")));
        assertEquals(ErrorCode.INTERNAL_ERROR, createError.getErrorCode());
        assertNull(createError.getCause());
        assertFalse(createError.getMessage().contains("private"));

        reset(mapper);
        when(mapper.findVisibleRoot(ROOT_ID)).thenReturn(root());
        when(mapper.findBlog(BLOG_ID)).thenReturn(BLOG_ID);
        when(mapper.findDirectReplies(BLOG_ID, ROOT_ID, null, 21))
                .thenThrow(new DataAccessResourceFailureException("private read details"));
        BusinessException readError = assertThrows(BusinessException.class,
                () -> service.listReplies(ROOT_ID, new BlogCommentPageRequest()));
        assertEquals(ErrorCode.INTERNAL_ERROR, readError.getErrorCode());
        assertNull(readError.getCause());
        assertFalse(readError.getMessage().contains("private"));
    }

    private void prepareRead() {
        when(mapper.findVisibleRoot(ROOT_ID)).thenReturn(root());
        when(mapper.findBlog(BLOG_ID)).thenReturn(BLOG_ID);
    }

    @Test
    void replyPageRequestsSizePlusOneAndUsesLastVisibleStringIdAsCursor() {
        prepareRead();
        when(mapper.findDirectReplies(BLOG_ID, ROOT_ID, null, 3))
                .thenReturn(Arrays.asList(row(903L, "newest"), row(902L, "visible"), row(901L, "lookahead")));
        BlogCommentPageRequest request = new BlogCommentPageRequest();
        request.setSize(2);

        BlogCommentPage page = service.listReplies(ROOT_ID, request);

        assertEquals(2, page.getItems().size());
        assertTrue(page.isHasNext());
        assertEquals("902", page.getNextBeforeId());
        assertEquals("903", page.getItems().get(0).getId());
        assertEquals("902", page.getItems().get(1).getId());
        verify(mapper).findDirectReplies(BLOG_ID, ROOT_ID, null, 3);
    }

    @Test
    void exactSizeAndEmptyReplyPagesHaveNoNextCursor() {
        prepareRead();
        BlogCommentPageRequest request = new BlogCommentPageRequest();
        request.setSize(2);
        when(mapper.findDirectReplies(BLOG_ID, ROOT_ID, null, 3))
                .thenReturn(Arrays.asList(row(903L, "first"), row(902L, "second")));
        BlogCommentPage exact = service.listReplies(ROOT_ID, request);
        assertEquals(2, exact.getItems().size());
        assertFalse(exact.isHasNext());
        assertNull(exact.getNextBeforeId());

        when(mapper.findDirectReplies(BLOG_ID, ROOT_ID, null, 3)).thenReturn(Collections.emptyList());
        BlogCommentPage empty = service.listReplies(ROOT_ID, request);
        assertTrue(empty.getItems().isEmpty());
        assertFalse(empty.isHasNext());
        assertNull(empty.getNextBeforeId());
    }

    @Test
    void beforeCursorIsPropagatedAndOnlySnapshotRootBlogIsQueried() {
        prepareRead();
        BlogCommentPageRequest request = new BlogCommentPageRequest();
        request.setSize(1);
        request.setBeforeId(444L);
        when(mapper.findDirectReplies(BLOG_ID, ROOT_ID, 444L, 2))
                .thenReturn(Collections.singletonList(row(443L, "older")));

        BlogCommentPage page = service.listReplies(ROOT_ID, request);

        assertFalse(page.isHasNext());
        assertNull(page.getNextBeforeId());
        assertEquals("443", page.getItems().get(0).getId());
        verify(mapper).findVisibleRoot(ROOT_ID);
        verify(mapper).findBlog(BLOG_ID);
        verify(mapper).findDirectReplies(BLOG_ID, ROOT_ID, 444L, 2);
        verifyNoMoreInteractions(mapper);
    }

    @Test
    void missingRootOrBlogReturnsNotFoundAndDoesNotQueryReplies() {
        when(mapper.findVisibleRoot(ROOT_ID)).thenReturn(null);
        assertError(ErrorCode.NOT_FOUND,
                () -> service.listReplies(ROOT_ID, new BlogCommentPageRequest()));
        verify(mapper).findVisibleRoot(ROOT_ID);
        verifyNoMoreInteractions(mapper);

        reset(mapper);
        when(mapper.findVisibleRoot(ROOT_ID)).thenReturn(root());
        when(mapper.findBlog(BLOG_ID)).thenReturn(null);
        assertError(ErrorCode.NOT_FOUND,
                () -> service.listReplies(ROOT_ID, new BlogCommentPageRequest()));
        verify(mapper).findVisibleRoot(ROOT_ID);
        verify(mapper).findBlog(BLOG_ID);
        verify(mapper, never()).findDirectReplies(anyLong(), anyLong(), any(), anyInt());
    }

    @Test
    void invalidReplyPageCannotReachDatabase() {
        assertError(ErrorCode.VALIDATION_FAILED,
                () -> service.listReplies(null, new BlogCommentPageRequest()));
        assertError(ErrorCode.VALIDATION_FAILED,
                () -> service.listReplies(0L, new BlogCommentPageRequest()));
        assertError(ErrorCode.VALIDATION_FAILED,
                () -> service.listReplies(-1L, new BlogCommentPageRequest()));
        assertError(ErrorCode.VALIDATION_FAILED, () -> service.listReplies(ROOT_ID, null));

        BlogCommentPageRequest request = new BlogCommentPageRequest();
        request.setSize(0);
        assertError(ErrorCode.VALIDATION_FAILED, () -> service.listReplies(ROOT_ID, request));
        request.setSize(51);
        assertError(ErrorCode.VALIDATION_FAILED, () -> service.listReplies(ROOT_ID, request));
        request.setSize(1);
        request.setBeforeId(0L);
        assertError(ErrorCode.VALIDATION_FAILED, () -> service.listReplies(ROOT_ID, request));
        verifyNoInteractions(mapper);
    }
}
