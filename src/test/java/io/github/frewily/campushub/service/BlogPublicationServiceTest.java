package io.github.frewily.campushub.service;

import io.github.frewily.campushub.dto.ApiModelMapper;
import io.github.frewily.campushub.dto.Result;
import io.github.frewily.campushub.dto.UserDTO;
import io.github.frewily.campushub.dto.request.BlogCreateRequest;
import io.github.frewily.campushub.entity.Blog;
import io.github.frewily.campushub.entity.BlogPublicationRequest;
import io.github.frewily.campushub.exception.BusinessException;
import io.github.frewily.campushub.exception.ErrorCode;
import io.github.frewily.campushub.mapper.BlogPublicationRequestMapper;
import io.github.frewily.campushub.utils.UserHolder;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import javax.validation.Validation;
import javax.validation.Validator;
import javax.validation.ValidatorFactory;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class BlogPublicationServiceTest {
    private static final long USER_ID = 7001L;
    private static final String KEY = "Campus_9-Retry";

    @Mock
    private IBlogService blogs;
    @Mock
    private BlogPublicationRequestMapper requests;

    private ValidatorFactory validatorFactory;
    private BlogPublicationService service;

    @BeforeEach
    void setUp() {
        validatorFactory = Validation.buildDefaultValidatorFactory();
        Validator validator = validatorFactory.getValidator();
        service = new BlogPublicationService(blogs, requests, validator);
        beginWritableUnitTransaction();
        UserDTO identity = new UserDTO();
        identity.setId(USER_ID);
        UserHolder.saveUser(identity);
    }

    @AfterEach
    void tearDown() {
        UserHolder.removeUser();
        TransactionSynchronizationManager.clear();
        validatorFactory.close();
    }

    @Test
    void firstPublicationReservesPersistsAndCompletesWithinWritableTransaction() {
        when(requests.reserve(eq(USER_ID), eq(KEY), any())).thenReturn(1);
        when(blogs.saveBlog(any(Blog.class))).thenReturn(Result.ok(501L));
        when(requests.complete(eq(USER_ID), eq(KEY), any(), eq(501L))).thenReturn(1);

        Result result = service.publish(validRequest(), KEY);

        assertEquals(Boolean.TRUE, result.getSuccess());
        assertEquals(501L, result.getData());
        ArgumentCaptor<Blog> saved = ArgumentCaptor.forClass(Blog.class);
        verify(blogs).saveBlog(saved.capture());
        assertEquals("Campus lunch", saved.getValue().getTitle());
        assertEquals("  /img/raw.jpg  ", saved.getValue().getImages());
        assertEquals("A unicode note 😀", saved.getValue().getContent());
        verify(requests).reserve(eq(USER_ID), eq(KEY), any());
        verify(requests).complete(eq(USER_ID), eq(KEY), any(), eq(501L));
        verifyNoMoreInteractions(blogs, requests);
    }

    @Test
    void duplicateMatchingReservationReplaysOnlyACompletedPositiveBlogId() {
        when(requests.reserve(eq(USER_ID), eq(KEY), any())).thenThrow(new DuplicateKeyException("duplicate"));
        when(requests.findForReplay(USER_ID, KEY)).thenReturn(new BlogPublicationRequest()
                .setUserId(USER_ID).setRequestKey(KEY).setRequestHash(
                        BlogPublicationService.fingerprint(ApiModelMapper.toBlog(validRequest())))
                .setBlogId(602L));

        Result result = service.publish(validRequest(), KEY);

        assertEquals(Boolean.TRUE, result.getSuccess());
        assertEquals(602L, result.getData());
        verify(requests).findForReplay(USER_ID, KEY);
        verifyNoInteractions(blogs);
        verify(requests, never()).complete(any(), any(), any(), any());
    }

    @Test
    void changedPayloadOrMalformedExistingReservationConflictsWithoutCreatingAnotherBlog() {
        when(requests.reserve(eq(USER_ID), eq(KEY), any())).thenThrow(new DuplicateKeyException("duplicate"));
        when(requests.findForReplay(USER_ID, KEY)).thenReturn(new BlogPublicationRequest()
                .setUserId(USER_ID).setRequestKey(KEY).setRequestHash("different-hash").setBlogId(603L));

        assertBusinessCode(ErrorCode.CONFLICT, () -> service.publish(validRequest(), KEY));
        verifyNoInteractions(blogs);
        verify(requests, never()).complete(any(), any(), any(), any());

        reset(requests);
        when(requests.reserve(eq(USER_ID), eq(KEY), any())).thenThrow(new DuplicateKeyException("duplicate"));
        when(requests.findForReplay(USER_ID, KEY)).thenReturn(null);
        assertBusinessCode(ErrorCode.INTERNAL_ERROR, () -> service.publish(validRequest(), KEY));

        reset(requests);
        when(requests.reserve(eq(USER_ID), eq(KEY), any())).thenThrow(new DuplicateKeyException("duplicate"));
        when(requests.findForReplay(USER_ID, KEY)).thenReturn(new BlogPublicationRequest()
                .setUserId(USER_ID).setRequestKey(KEY).setRequestHash(
                        BlogPublicationService.fingerprint(ApiModelMapper.toBlog(validRequest()))).setBlogId(null));
        assertBusinessCode(ErrorCode.INTERNAL_ERROR, () -> service.publish(validRequest(), KEY));
        verifyNoInteractions(blogs);
    }

    @Test
    void identityKeyAndDtoAreRejectedBeforeAnyReservation() {
        UserHolder.removeUser();
        assertBusinessCode(ErrorCode.AUTHENTICATION_FAILED, () -> service.publish(validRequest(), KEY));
        UserDTO invalidIdentity = new UserDTO();
        invalidIdentity.setId(0L);
        UserHolder.saveUser(invalidIdentity);
        assertBusinessCode(ErrorCode.AUTHENTICATION_FAILED, () -> service.publish(validRequest(), KEY));

        UserDTO validIdentity = new UserDTO();
        validIdentity.setId(USER_ID);
        UserHolder.saveUser(validIdentity);
        for (String invalid : new String[]{null, "", " ", " leading", "trailing ", "a,b", "a/b",
                "café", repeat('x', 65)}) {
            assertBusinessCode(ErrorCode.VALIDATION_FAILED, () -> service.publish(validRequest(), invalid));
        }
        BlogCreateRequest invalidRequest = new BlogCreateRequest();
        invalidRequest.setTitle("  ");
        invalidRequest.setContent("body");
        assertBusinessCode(ErrorCode.VALIDATION_FAILED, () -> service.publish(invalidRequest, KEY));
        assertBusinessCode(ErrorCode.VALIDATION_FAILED, () -> service.publish(null, KEY));
        assertBusinessCode(ErrorCode.VALIDATION_FAILED,
                () -> service.publish(request(repeat('t', 256), "", "body"), KEY));
        assertBusinessCode(ErrorCode.VALIDATION_FAILED,
                () -> service.publish(request("title", "", repeat('c', 2049)), KEY));
        assertBusinessCode(ErrorCode.VALIDATION_FAILED,
                () -> service.publish(request("title", repeat(' ', 2049), "body"), KEY));
        BlogCreateRequest store = request("title", "", "body");
        store.setShopId(1L);
        assertBusinessCode(ErrorCode.VALIDATION_FAILED, () -> service.publish(store, KEY));
        store.setShopId(0L);
        store.setImages("image");
        assertBusinessCode(ErrorCode.VALIDATION_FAILED, () -> service.publish(store, KEY));
        assertDoesNotThrow(() -> BlogPublicationService.validateKey("a"));
        assertDoesNotThrow(() -> BlogPublicationService.validateKey(repeat('a', 64)));
        verifyNoInteractions(requests, blogs);
    }

    @Test
    void writableTransactionIsRequiredBeforeTheFirstMapperCall() {
        TransactionSynchronizationManager.clear();
        assertThrows(IllegalStateException.class, () -> service.publish(validRequest(), KEY));
        verifyNoInteractions(requests, blogs);

        TransactionSynchronizationManager.setActualTransactionActive(true);
        assertThrows(IllegalStateException.class, () -> service.publish(validRequest(), KEY));
        verifyNoInteractions(requests, blogs);

        TransactionSynchronizationManager.initSynchronization();
        TransactionSynchronizationManager.setCurrentTransactionReadOnly(true);
        assertThrows(IllegalStateException.class, () -> service.publish(validRequest(), KEY));
        verifyNoInteractions(requests, blogs);
    }

    @Test
    void mapperAndPersistenceFailuresBecomeCauseFreeInternalErrors() {
        DataAccessResourceFailureException databaseFailure = new DataAccessResourceFailureException("db down");
        when(requests.reserve(eq(USER_ID), eq(KEY), any())).thenThrow(databaseFailure);
        BusinessException thrown = assertThrows(BusinessException.class, () -> service.publish(validRequest(), KEY));
        assertEquals(ErrorCode.INTERNAL_ERROR, thrown.getErrorCode());
        assertNull(thrown.getCause());
        verifyNoInteractions(blogs);

        reset(requests);
        when(requests.reserve(eq(USER_ID), eq(KEY), any())).thenReturn(1);
        when(blogs.saveBlog(any(Blog.class))).thenThrow(new DataAccessResourceFailureException("persistence failure"));
        thrown = assertThrows(BusinessException.class, () -> service.publish(validRequest(), KEY));
        assertEquals(ErrorCode.INTERNAL_ERROR, thrown.getErrorCode());
        assertNull(thrown.getCause());
        verify(requests, never()).complete(any(), any(), any(), any());
    }

    @Test
    void unconfirmedReservationCompletionOrInvalidBlogResultFailsClosed() {
        when(requests.reserve(eq(USER_ID), eq(KEY), any())).thenReturn(0);
        assertBusinessCode(ErrorCode.OPERATION_FAILED, () -> service.publish(validRequest(), KEY));
        verifyNoInteractions(blogs);

        reset(requests);
        when(requests.reserve(eq(USER_ID), eq(KEY), any())).thenReturn(1);
        when(blogs.saveBlog(any(Blog.class))).thenReturn(Result.ok(701L));
        when(requests.complete(eq(USER_ID), eq(KEY), any(), eq(701L))).thenReturn(0);
        assertBusinessCode(ErrorCode.OPERATION_FAILED, () -> service.publish(validRequest(), KEY));

        reset(requests, blogs);
        when(requests.reserve(eq(USER_ID), eq(KEY), any())).thenReturn(1);
        when(blogs.saveBlog(any(Blog.class))).thenReturn(Result.ok(0L));
        assertBusinessCode(ErrorCode.INTERNAL_ERROR, () -> service.publish(validRequest(), KEY));
        verify(requests, never()).complete(any(), any(), any(), any());

        reset(requests, blogs);
        when(requests.reserve(eq(USER_ID), eq(KEY), any())).thenReturn(1);
        when(blogs.saveBlog(any(Blog.class))).thenReturn(Result.ok("702"));
        assertBusinessCode(ErrorCode.INTERNAL_ERROR, () -> service.publish(validRequest(), KEY));
        verify(requests, never()).complete(any(), any(), any(), any());

        reset(requests, blogs);
        when(requests.reserve(eq(USER_ID), eq(KEY), any())).thenReturn(1);
        when(blogs.saveBlog(any(Blog.class))).thenReturn(Result.fail(ErrorCode.OPERATION_FAILED));
        assertBusinessCode(ErrorCode.INTERNAL_ERROR, () -> service.publish(validRequest(), KEY));
        verify(requests, never()).complete(any(), any(), any(), any());
    }

    @Test
    void requestKeyAndAuthorScopeArePassedThroughWithoutCaseFolding() {
        when(requests.reserve(any(), any(), any())).thenReturn(1);
        when(blogs.saveBlog(any(Blog.class))).thenReturn(Result.ok(801L), Result.ok(802L));
        when(requests.complete(any(), any(), any(), any())).thenReturn(1);
        service.publish(validRequest(), "CaseKey");
        UserDTO secondAuthor = new UserDTO();
        secondAuthor.setId(USER_ID + 1);
        UserHolder.saveUser(secondAuthor);
        service.publish(validRequest(), "casekey");

        verify(requests).reserve(eq(USER_ID), eq("CaseKey"), any());
        verify(requests).reserve(eq(USER_ID + 1), eq("casekey"), any());
        verify(requests).complete(eq(USER_ID), eq("CaseKey"), any(), eq(801L));
        verify(requests).complete(eq(USER_ID + 1), eq("casekey"), any(), eq(802L));
    }

    @Test
    void fingerprintUsesNormalizedFourFieldsAndUnambiguousUnicodeLengthPrefixes() {
        // Independently encoded v1 vector: retained receipts must not silently change fingerprint rules.
        assertEquals("b530daf2621e51c250524f008b877f39a70dfb1841c47a2c8e29c98143239b5d",
                BlogPublicationService.fingerprint(ApiModelMapper.toBlog(validRequest())));
        BlogCreateRequest omittedImages = request("title", null, "content");
        BlogCreateRequest blankImages = request("title", "  \t", "content");
        BlogCreateRequest emptyImages = request("title", "", "content");
        assertEquals(BlogPublicationService.fingerprint(ApiModelMapper.toBlog(omittedImages)),
                BlogPublicationService.fingerprint(ApiModelMapper.toBlog(blankImages)));
        assertEquals(BlogPublicationService.fingerprint(ApiModelMapper.toBlog(omittedImages)),
                BlogPublicationService.fingerprint(ApiModelMapper.toBlog(emptyImages)));

        Blog first = new Blog().setShopId(1L).setTitle("a|b").setImages("").setContent("c");
        Blog second = new Blog().setShopId(1L).setTitle("a").setImages("").setContent("b|c");
        assertNotEquals(BlogPublicationService.fingerprint(first), BlogPublicationService.fingerprint(second));
        assertDoesNotThrow(() -> BlogPublicationService.fingerprint(
                new Blog().setShopId(2L).setTitle("标题😀").setImages("图像").setContent("内容é")));
        assertNotEquals(BlogPublicationService.fingerprint(
                        new Blog().setShopId(2L).setTitle("标题😀").setImages("图像").setContent("内容é")),
                BlogPublicationService.fingerprint(
                        new Blog().setShopId(2L).setTitle("标题😁").setImages("图像").setContent("内容é")));
        assertNotEquals(BlogPublicationService.fingerprint(
                        new Blog().setTitle("\uD800").setImages("").setContent("body")),
                BlogPublicationService.fingerprint(
                        new Blog().setTitle("\uD801").setImages("").setContent("body")));

        Blog mapped = ApiModelMapper.toBlog(request("same", " /raw ", "same body"));
        String expected = BlogPublicationService.fingerprint(mapped);
        mapped.setId(999L).setUserId(123L).setLiked(88).setComments(77).setCreateTime(
                java.time.LocalDateTime.of(2000, 1, 1, 0, 0));
        assertEquals(expected, BlogPublicationService.fingerprint(mapped));
        assertNotEquals(expected, BlogPublicationService.fingerprint(
                new Blog().setShopId(mapped.getShopId()).setTitle("changed").setImages(mapped.getImages())
                        .setContent(mapped.getContent())));
        assertNotEquals(expected, BlogPublicationService.fingerprint(
                new Blog().setShopId(mapped.getShopId()).setTitle(" same ").setImages(mapped.getImages())
                        .setContent(mapped.getContent())));
        assertNotEquals(expected, BlogPublicationService.fingerprint(
                new Blog().setShopId(9L).setTitle(mapped.getTitle()).setImages(mapped.getImages())
                        .setContent(mapped.getContent())));
        assertNotEquals(expected, BlogPublicationService.fingerprint(
                new Blog().setShopId(mapped.getShopId()).setTitle(mapped.getTitle()).setImages("/different")
                        .setContent(mapped.getContent())));
        assertNotEquals(expected, BlogPublicationService.fingerprint(
                new Blog().setShopId(mapped.getShopId()).setTitle(mapped.getTitle()).setImages(mapped.getImages())
                        .setContent("changed body")));
        assertNotEquals(expected, BlogPublicationService.fingerprint(
                new Blog().setShopId(mapped.getShopId()).setTitle(mapped.getTitle()).setImages(mapped.getImages())
                        .setContent(" same body ")));
    }

    private static void beginWritableUnitTransaction() {
        TransactionSynchronizationManager.initSynchronization();
        TransactionSynchronizationManager.setActualTransactionActive(true);
        TransactionSynchronizationManager.setCurrentTransactionReadOnly(false);
    }

    private static BlogCreateRequest validRequest() {
        return request("Campus lunch", "  /img/raw.jpg  ", "A unicode note 😀");
    }

    private static BlogCreateRequest request(String title, String images, String content) {
        BlogCreateRequest request = new BlogCreateRequest();
        request.setTitle(title);
        request.setImages(images);
        request.setContent(content);
        return request;
    }

    private static void assertBusinessCode(ErrorCode expected, org.junit.jupiter.api.function.Executable action) {
        assertEquals(expected, assertThrows(BusinessException.class, action).getErrorCode());
    }

    private static String repeat(char value, int count) {
        char[] chars = new char[count];
        java.util.Arrays.fill(chars, value);
        return new String(chars);
    }
}
