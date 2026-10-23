package io.github.frewily.campushub.service.impl;

import io.github.frewily.campushub.dto.UserDTO;
import io.github.frewily.campushub.dto.request.BlogEditRequest;
import io.github.frewily.campushub.entity.Blog;
import io.github.frewily.campushub.exception.BusinessException;
import io.github.frewily.campushub.exception.ErrorCode;
import io.github.frewily.campushub.mapper.BlogMapper;
import io.github.frewily.campushub.content.application.BlogUserReader;
import io.github.frewily.campushub.social.application.FollowerReader;
import io.github.frewily.campushub.utils.UserHolder;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.test.util.ReflectionTestUtils;

import javax.validation.Validation;
import javax.validation.Validator;
import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class BlogEditServiceTest {
    private static final long BLOG_ID = 9007199254740997L;
    private static final long OWNER_ID = 9007199254740993L;
    private BlogMapper mapper;
    private BlogUserReader blogUserReader;
    private FollowerReader followerReader;
    private StringRedisTemplate stringRedisTemplate;
    private BlogServiceImpl service;

    @BeforeEach
    void setUp() {
        mapper = mock(BlogMapper.class);
        blogUserReader = mock(BlogUserReader.class);
        followerReader = mock(FollowerReader.class);
        stringRedisTemplate = mock(StringRedisTemplate.class);
        service = new BlogServiceImpl();
        ReflectionTestUtils.setField(service, "baseMapper", mapper);
        ReflectionTestUtils.setField(service, "validator", validator());
        ReflectionTestUtils.setField(service, "blogUserReader", blogUserReader);
        ReflectionTestUtils.setField(service, "followerReader", followerReader);
        ReflectionTestUtils.setField(service, "stringRedisTemplate", stringRedisTemplate);
        saveUser(OWNER_ID);
    }

    @AfterEach
    void clearUser() {
        UserHolder.removeUser();
    }

    private static Validator validator() {
        return Validation.buildDefaultValidatorFactory().getValidator();
    }

    private static BlogEditRequest request(String title, String images, String content) {
        BlogEditRequest request = new BlogEditRequest();
        request.setTitle(title);
        request.setImages(images);
        request.setContent(content);
        return request;
    }

    private static Blog stored(Long shopId, String title, String images, String content) {
        return new Blog().setId(BLOG_ID).setUserId(OWNER_ID).setShopId(shopId)
                .setTitle(title).setImages(images).setContent(content);
    }

    private static void saveUser(Long id) {
        UserDTO user = new UserDTO();
        user.setId(id);
        UserHolder.saveUser(user);
    }

    private void assertError(ErrorCode expected, org.junit.jupiter.api.function.Executable action) {
        BusinessException error = assertThrows(BusinessException.class, action);
        assertEquals(expected, error.getErrorCode());
    }

    private void assertNoOtherResourceInteractions() {
        verifyNoInteractions(blogUserReader, followerReader, stringRedisTemplate);
    }

    @Test
    void rejectsInvalidIdsRequestsAndIdentitiesBeforeMapperAccess() {
        for (Long id : new Long[]{null, 0L, -1L}) {
            assertError(ErrorCode.VALIDATION_FAILED, () -> service.editBlog(id, request("t", "", "c")));
        }
        for (BlogEditRequest invalid : Arrays.asList(null, request(" ", "", "c"), request("t", "", "\t"),
                request(repeat('t', 256), "", "c"), request(repeat('x', 2049), "", "c"),
                request("t", repeat(' ', 2049), "c"),
                request("t", "", repeat('c', 2049)))) {
            assertError(ErrorCode.VALIDATION_FAILED, () -> service.editBlog(BLOG_ID, invalid));
        }
        for (Long id : new Long[]{null, 0L, -1L}) {
            saveUser(id);
            assertError(ErrorCode.AUTHENTICATION_FAILED,
                    () -> service.editBlog(BLOG_ID, request("t", "", "c")));
        }
        UserHolder.removeUser();
        assertError(ErrorCode.AUTHENTICATION_FAILED,
                () -> service.editBlog(BLOG_ID, request("t", "", "c")));
        verifyNoInteractions(mapper);
        assertNoOtherResourceInteractions();
    }

    @Test
    void ownerCanEditOnlyContentFieldsAndMapperReceivesOriginalNonblankImageString() {
        when(mapper.findBlogForAuthorEdit(BLOG_ID)).thenReturn(stored(null, "old", "old-image", "old-body"));
        when(mapper.editBlogFields(BLOG_ID, OWNER_ID, "new", "  image-list  ", "new-body")).thenReturn(1);

        service.editBlog(BLOG_ID, request("new", "  image-list  ", "new-body"));

        verify(mapper).findBlogForAuthorEdit(BLOG_ID);
        verify(mapper).editBlogFields(BLOG_ID, OWNER_ID, "new", "  image-list  ", "new-body");
        verifyNoMoreInteractions(mapper);
        assertNoOtherResourceInteractions();
    }

    @Test
    void missingRowIsNotFoundAndForeignOwnerIncludingAdminIdentityIsForbidden() {
        when(mapper.findBlogForAuthorEdit(BLOG_ID)).thenReturn(null);
        assertError(ErrorCode.NOT_FOUND, () -> service.editBlog(BLOG_ID, request("t", "", "c")));
        verify(mapper).findBlogForAuthorEdit(BLOG_ID);
        verifyNoMoreInteractions(mapper);

        reset(mapper);
        when(mapper.findBlogForAuthorEdit(BLOG_ID)).thenReturn(stored(null, "old", "", "old-body"));
        saveUser(42L); // Service authorization is owner-only; the HTTP ADMIN role does not grant override.
        assertError(ErrorCode.AUTHORIZATION_FAILED, () -> service.editBlog(BLOG_ID, request("t", "", "c")));
        verify(mapper).findBlogForAuthorEdit(BLOG_ID);
        verifyNoMoreInteractions(mapper);
        assertNoOtherResourceInteractions();
    }

    @Test
    void associatedShopRequiresNonblankImagesWhileUnassociatedNullOrBlankImagesNormalizeToEmpty() {
        when(mapper.findBlogForAuthorEdit(BLOG_ID)).thenReturn(stored(7L, "same", "image", "same-body"));
        for (String images : new String[]{null, "", " \t\n"}) {
            assertError(ErrorCode.VALIDATION_FAILED,
                    () -> service.editBlog(BLOG_ID, request("same", images, "same-body")));
        }
        verify(mapper, times(3)).findBlogForAuthorEdit(BLOG_ID);
        verifyNoMoreInteractions(mapper);

        reset(mapper);
        when(mapper.findBlogForAuthorEdit(BLOG_ID)).thenReturn(stored(null, "same", "", "same-body"));
        for (String images : new String[]{null, "", " \t\n"}) {
            service.editBlog(BLOG_ID, request("same", images, "same-body"));
        }
        verify(mapper, times(3)).findBlogForAuthorEdit(BLOG_ID);
        verify(mapper, never()).editBlogFields(anyLong(), anyLong(), anyString(), anyString(), anyString());
        verifyNoMoreInteractions(mapper);
        assertNoOtherResourceInteractions();
    }

    @Test
    void exactNoOpSkipsUpdateAndChangedEditMustAffectExactlyOneRow() {
        when(mapper.findBlogForAuthorEdit(BLOG_ID)).thenReturn(stored(null, "same", "image", "same-body"));
        service.editBlog(BLOG_ID, request("same", "image", "same-body"));
        verify(mapper).findBlogForAuthorEdit(BLOG_ID);
        verify(mapper, never()).editBlogFields(anyLong(), anyLong(), anyString(), anyString(), anyString());

        reset(mapper);
        when(mapper.findBlogForAuthorEdit(BLOG_ID)).thenReturn(stored(null, "old", "image", "old-body"));
        when(mapper.editBlogFields(BLOG_ID, OWNER_ID, "new", "image", "new-body")).thenReturn(0);
        assertError(ErrorCode.OPERATION_FAILED,
                () -> service.editBlog(BLOG_ID, request("new", "image", "new-body")));
        verify(mapper).findBlogForAuthorEdit(BLOG_ID);
        verify(mapper).editBlogFields(BLOG_ID, OWNER_ID, "new", "image", "new-body");
        verifyNoMoreInteractions(mapper);
        assertNoOtherResourceInteractions();
    }

    @Test
    void dataAccessFailuresBecomeGenericInternalErrorWithoutCause() {
        when(mapper.findBlogForAuthorEdit(BLOG_ID)).thenThrow(
                new DataAccessResourceFailureException("private lookup details"));
        assertGenericInternalError();

        reset(mapper);
        when(mapper.findBlogForAuthorEdit(BLOG_ID)).thenReturn(stored(null, "old", "", "old-body"));
        when(mapper.editBlogFields(BLOG_ID, OWNER_ID, "new", "", "new-body")).thenThrow(
                new DataAccessResourceFailureException("private update details"));
        assertGenericInternalError();
        assertNoOtherResourceInteractions();
    }

    private void assertGenericInternalError() {
        BusinessException error = assertThrows(BusinessException.class,
                () -> service.editBlog(BLOG_ID, request("new", "", "new-body")));
        assertEquals(ErrorCode.INTERNAL_ERROR, error.getErrorCode());
        assertNull(error.getCause());
        assertFalse(error.getMessage().contains("private"));
    }

    private static String repeat(char value, int count) {
        char[] chars = new char[count];
        Arrays.fill(chars, value);
        return new String(chars);
    }
}
