package io.github.frewily.campushub.service.impl;

import io.github.frewily.campushub.dto.UserDTO;
import io.github.frewily.campushub.dto.request.BlogCommentCreateRequest;
import io.github.frewily.campushub.dto.request.BlogCommentPageRequest;
import io.github.frewily.campushub.dto.response.BlogCommentPage;
import io.github.frewily.campushub.entity.BlogComments;
import io.github.frewily.campushub.exception.*;
import io.github.frewily.campushub.mapper.BlogCommentsMapper;
import io.github.frewily.campushub.utils.UserHolder;
import org.junit.jupiter.api.*;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.test.util.ReflectionTestUtils;
import javax.validation.*;
import java.time.LocalDateTime;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class BlogCommentsServiceImplTest {
    private static ValidatorFactory factory;
    private BlogCommentsMapper mapper;
    private BlogCommentsServiceImpl service;
    @BeforeAll static void validation() { factory = Validation.buildDefaultValidatorFactory(); }
    @AfterAll static void close() { factory.close(); }
    @BeforeEach void setup() {
        mapper = mock(BlogCommentsMapper.class);
        service = new BlogCommentsServiceImpl(factory.getValidator());
        ReflectionTestUtils.setField(service, "baseMapper", mapper);
        UserDTO user = new UserDTO(); user.setId(9007199254740993L); UserHolder.saveUser(user);
    }
    @AfterEach void clearUser() { UserHolder.removeUser(); }
    private BlogCommentCreateRequest create() {
        BlogCommentCreateRequest request = new BlogCommentCreateRequest();
        request.setBlogId(7L); request.setContent("plain <script>untrusted</script> text"); return request;
    }
    private BlogComments row(long id) {
        return new BlogComments().setId(id).setBlogId(7L).setUserId(9007199254740993L)
                .setContent("synthetic").setCreateTime(LocalDateTime.of(2026, 1, 1, 0, 0));
    }
    private void insertSuccess() {
        when(mapper.lockBlog(7L)).thenReturn(7L);
        when(mapper.insert(any(BlogComments.class))).thenAnswer(call -> {
            BlogComments entity = call.getArgument(0);
            assertEquals(9007199254740993L, entity.getUserId());
            assertEquals(0L, entity.getParentId()); assertEquals(0L, entity.getAnswerId());
            assertEquals(0, entity.getLiked()); assertEquals(0, entity.getStatus());
            assertNull(entity.getId()); assertNull(entity.getCreateTime());
            entity.setId(9007199254740995L); return 1;
        });
    }
    @Test void createUsesSessionAndServerControlledFieldsAndReadBackTime() {
        insertSuccess(); when(mapper.incrementCommentCount(7L)).thenReturn(1);
        when(mapper.selectById(9007199254740995L)).thenReturn(row(9007199254740995L));
        io.github.frewily.campushub.dto.response.BlogCommentItem item = service.createComment(create());
        assertEquals("9007199254740995", item.getId());
        assertEquals("9007199254740993", item.getUserId());
        org.mockito.InOrder order = inOrder(mapper);
        order.verify(mapper).lockBlog(7L); order.verify(mapper).insert(any(BlogComments.class));
        order.verify(mapper).incrementCommentCount(7L); order.verify(mapper).selectById(9007199254740995L);
    }
    @Test void invalidCreateCannotReachDatabaseEvenThroughService() {
        List<BlogCommentCreateRequest> invalid = new ArrayList<>(); invalid.add(null);
        BlogCommentCreateRequest missing = create(); missing.setBlogId(null); invalid.add(missing);
        BlogCommentCreateRequest negative = create(); negative.setBlogId(-1L); invalid.add(negative);
        BlogCommentCreateRequest blank = create(); blank.setContent("  \n"); invalid.add(blank);
        BlogCommentCreateRequest longText = create(); longText.setContent(String.join("", Collections.nCopies(256, "x"))); invalid.add(longText);
        for (BlogCommentCreateRequest request : invalid) assertError(ErrorCode.VALIDATION_FAILED, () -> service.createComment(request));
        verifyNoInteractions(mapper);
    }
    @Test void noAuthenticatedAuthorCannotReachDatabase() {
        UserHolder.removeUser(); assertError(ErrorCode.AUTHENTICATION_FAILED, () -> service.createComment(create()));
        UserDTO invalid = new UserDTO(); invalid.setId(0L); UserHolder.saveUser(invalid);
        assertError(ErrorCode.AUTHENTICATION_FAILED, () -> service.createComment(create())); verifyNoInteractions(mapper);
    }
    @Test void missingBlogCannotInsertOrIncrement() {
        when(mapper.lockBlog(7L)).thenReturn(null);
        assertError(ErrorCode.NOT_FOUND, () -> service.createComment(create()));
        verify(mapper).lockBlog(7L); verifyNoMoreInteractions(mapper);
    }
    @Test void zeroInsertCannotIncrement() {
        when(mapper.lockBlog(7L)).thenReturn(7L);
        assertError(ErrorCode.OPERATION_FAILED, () -> service.createComment(create()));
        verify(mapper, never()).incrementCommentCount(anyLong());
    }
    @Test void zeroCounterAndMissingReadBackFailInsteadOfSuccess() {
        insertSuccess(); assertError(ErrorCode.OPERATION_FAILED, () -> service.createComment(create()));
        when(mapper.incrementCommentCount(7L)).thenReturn(1);
        assertError(ErrorCode.OPERATION_FAILED, () -> service.createComment(create()));
    }
    @Test void databaseErrorDoesNotLeakPrivateDetails() {
        when(mapper.lockBlog(7L)).thenThrow(new DataAccessResourceFailureException("private SQL/credential"));
        BusinessException error = assertThrows(BusinessException.class, () -> service.createComment(create()));
        assertEquals(ErrorCode.INTERNAL_ERROR, error.getErrorCode()); assertNull(error.getCause());
        assertFalse(error.getMessage().contains("private"));
    }
    @Test void boundedPageUsesLastReturnedIdNotExtraRow() {
        when(mapper.findBlog(7L)).thenReturn(7L);
        when(mapper.findFirstLevelComments(7L, null, 3)).thenReturn(Arrays.asList(row(9), row(8), row(7)));
        BlogCommentPageRequest request = new BlogCommentPageRequest(); request.setSize(2);
        BlogCommentPage page = service.listComments(7L, request);
        assertEquals(2, page.getItems().size()); assertTrue(page.isHasNext()); assertEquals("8", page.getNextBeforeId());
        request.setBeforeId(8L);
        when(mapper.findFirstLevelComments(7L, 8L, 3)).thenReturn(Collections.singletonList(row(7)));
        page = service.listComments(7L, request); assertFalse(page.isHasNext()); assertNull(page.getNextBeforeId());
        assertEquals("7", page.getItems().get(0).getId());
    }
    @Test void emptyExistingBlogIsEmptyPageNotNotFound() {
        when(mapper.findBlog(7L)).thenReturn(7L);
        when(mapper.findFirstLevelComments(7L, null, 21)).thenReturn(Collections.emptyList());
        BlogCommentPage page = service.listComments(7L, new BlogCommentPageRequest());
        assertTrue(page.getItems().isEmpty()); assertFalse(page.isHasNext()); assertNull(page.getNextBeforeId());
    }
    @Test void missingBlogReadDoesNotQueryComments() {
        when(mapper.findBlog(7L)).thenReturn(null);
        assertError(ErrorCode.NOT_FOUND, () -> service.listComments(7L, new BlogCommentPageRequest()));
        verify(mapper).findBlog(7L); verifyNoMoreInteractions(mapper);
    }
    @Test void invalidPageCannotReachDatabase() {
        assertError(ErrorCode.VALIDATION_FAILED, () -> service.listComments(0L, new BlogCommentPageRequest()));
        assertError(ErrorCode.VALIDATION_FAILED, () -> service.listComments(null, new BlogCommentPageRequest()));
        assertError(ErrorCode.VALIDATION_FAILED, () -> service.listComments(7L, null));
        BlogCommentPageRequest request = new BlogCommentPageRequest(); request.setSize(51);
        assertError(ErrorCode.VALIDATION_FAILED, () -> service.listComments(7L, request));
        request.setSize(1); request.setBeforeId(0L);
        assertError(ErrorCode.VALIDATION_FAILED, () -> service.listComments(7L, request)); verifyNoInteractions(mapper);
    }
    @Test void readDatabaseErrorIsNotInventedEmptyPage() {
        when(mapper.findBlog(7L)).thenReturn(7L);
        when(mapper.findFirstLevelComments(7L, null, 21)).thenThrow(new DataAccessResourceFailureException("private"));
        assertError(ErrorCode.INTERNAL_ERROR, () -> service.listComments(7L, new BlogCommentPageRequest()));
    }
    private void assertError(ErrorCode code, org.junit.jupiter.api.function.Executable action) {
        assertEquals(code, assertThrows(BusinessException.class, action).getErrorCode());
    }
}
