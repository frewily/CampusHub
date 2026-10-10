package io.github.frewily.campushub.controller;

import io.github.frewily.campushub.config.GlobalExceptionHandler;
import io.github.frewily.campushub.config.SecurityConfig;
import io.github.frewily.campushub.dto.request.BlogCommentCreateRequest;
import io.github.frewily.campushub.dto.request.BlogCommentPageRequest;
import io.github.frewily.campushub.dto.response.BlogCommentItem;
import io.github.frewily.campushub.dto.response.BlogCommentPage;
import io.github.frewily.campushub.mapper.AccountAccessMapper;
import io.github.frewily.campushub.security.RedisTokenAuthenticationFilter;
import io.github.frewily.campushub.security.ResourceAuthorizationService;
import io.github.frewily.campushub.security.SecurityErrorResponder;
import io.github.frewily.campushub.service.IBlogCommentsService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.test.web.servlet.MockMvc;

import java.time.LocalDateTime;
import java.util.Collections;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import org.springframework.http.MediaType;
import org.springframework.data.redis.core.HashOperations;
import static io.github.frewily.campushub.utils.RedisConstants.LOGIN_USER_KEY;

@SpringBootTest(classes = BlogCommentsHttpContractTest.TestApplication.class)
@AutoConfigureMockMvc
class BlogCommentsHttpContractTest {
    private static final String LARGE_ID = "9007199254740993";

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private IBlogCommentsService commentsService;
    @MockBean
    private StringRedisTemplate stringRedisTemplate;
    @MockBean
    private HashOperations<String, Object, Object> hashOperations;
    @MockBean
    private AccountAccessMapper accountAccessMapper;

    @SpringBootConfiguration
    @EnableAutoConfiguration
    @Import({BlogCommentsController.class, SecurityConfig.class, SecurityErrorResponder.class,
            RedisTokenAuthenticationFilter.class, ResourceAuthorizationService.class,
            GlobalExceptionHandler.class})
    static class TestApplication {
    }

    @Test
    void listUsesDefaultPageSizeAndExposesOnlyPublicPageFields() throws Exception {
        prepareUserSession();
        when(commentsService.listComments(eq(9007199254740995L), any(BlogCommentPageRequest.class)))
                .thenReturn(new BlogCommentPage(Collections.singletonList(
                        new BlogCommentItem(LARGE_ID, "9007199254740995", "9007199254740994",
                                "Nice", LocalDateTime.of(2026, 10, 10, 12, 0))), true, LARGE_ID));

        mockMvc.perform(get("/blog-comments/of/blog/9007199254740995").header("authorization", "token"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items[0].id").value(LARGE_ID))
                .andExpect(jsonPath("$.data.items[0].blogId").value("9007199254740995"))
                .andExpect(jsonPath("$.data.items[0].userId").value("9007199254740994"))
                .andExpect(jsonPath("$.data.items[0].content").value("Nice"))
                .andExpect(jsonPath("$.data.items[0].parentId").doesNotExist())
                .andExpect(jsonPath("$.data.items[0].status").doesNotExist())
                .andExpect(jsonPath("$.data.hasNext").value(true))
                .andExpect(jsonPath("$.data.nextBeforeId").value(LARGE_ID));

        org.mockito.ArgumentCaptor<BlogCommentPageRequest> captor =
                org.mockito.ArgumentCaptor.forClass(BlogCommentPageRequest.class);
        verify(commentsService).listComments(eq(9007199254740995L), captor.capture());
        assertEquals(20, captor.getValue().getSize());
        assertEquals(null, captor.getValue().getBeforeId());
    }

    @Test
    void validCreateRequestBindsWhitelistAndReturnsStringIdentifiers() throws Exception {
        prepareUserSession();
        when(commentsService.createComment(any(BlogCommentCreateRequest.class)))
                .thenReturn(new BlogCommentItem(LARGE_ID, "9007199254740995", "9007199254740994",
                        "Nice", LocalDateTime.of(2026, 10, 10, 12, 0)));

        mockMvc.perform(post("/blog-comments").header("authorization", "token")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"blogId\":\"9007199254740995\",\"content\":\"Nice\","
                                + "\"userId\":\"8\",\"status\":false,\"parentId\":2,\"extra\":\"ignored\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.id").value(LARGE_ID))
                .andExpect(jsonPath("$.data.blogId").value("9007199254740995"))
                .andExpect(jsonPath("$.data.userId").value("9007199254740994"))
                .andExpect(jsonPath("$.data.parentId").doesNotExist())
                .andExpect(jsonPath("$.data.status").doesNotExist());

        org.mockito.ArgumentCaptor<BlogCommentCreateRequest> captor =
                org.mockito.ArgumentCaptor.forClass(BlogCommentCreateRequest.class);
        verify(commentsService).createComment(captor.capture());
        assertEquals(9007199254740995L, captor.getValue().getBlogId());
        assertEquals("Nice", captor.getValue().getContent());
    }

    @Test
    void invalidCreatePayloadsAreRejectedBeforeServiceCall() throws Exception {
        prepareUserSession();
        StringBuilder longContent = new StringBuilder();
        for (int i = 0; i < 256; i++) longContent.append('x');
        for (String body : new String[]{"{", "null", "{}", "{\"blogId\":0,\"content\":\"x\"}",
                "{\"blogId\":1,\"content\":\" \"}",
                "{\"blogId\":1,\"content\":\"" + longContent + "\"}"}) {
            mockMvc.perform(post("/blog-comments").header("authorization", "token")
                            .contentType(MediaType.APPLICATION_JSON).content(body))
                    .andExpect(status().isBadRequest());
        }
        verifyNoInteractions(commentsService);
    }

    @Test
    void invalidBlogIdsAndPageSizesAreRejectedBeforeServiceCall() throws Exception {
        prepareUserSession();
        for (String path : new String[]{"/blog-comments/of/blog/0", "/blog-comments/of/blog/-1",
                "/blog-comments/of/blog/not-a-number"}) {
            mockMvc.perform(get(path).header("authorization", "token")).andExpect(status().isBadRequest());
        }
        for (String size : new String[]{"0", "51", "bad"}) {
            mockMvc.perform(get("/blog-comments/of/blog/1").header("authorization", "token").param("size", size))
                    .andExpect(status().isBadRequest());
        }
        mockMvc.perform(get("/blog-comments/of/blog/1").header("authorization", "token").param("beforeId", "0"))
                .andExpect(status().isBadRequest());
        verifyNoInteractions(commentsService);
    }

    private void prepareUserSession() {
        when(stringRedisTemplate.opsForHash()).thenReturn(hashOperations);
        java.util.Map<Object, Object> session = new java.util.HashMap<>();
        session.put("id", "21");
        session.put("nickName", "commenter");
        when(hashOperations.entries(LOGIN_USER_KEY + "token")).thenReturn(session);
        when(accountAccessMapper.findAccountStatus(21L)).thenReturn("ACTIVE");
        when(accountAccessMapper.findRoles(21L)).thenReturn(Collections.singletonList("USER"));
    }
}
