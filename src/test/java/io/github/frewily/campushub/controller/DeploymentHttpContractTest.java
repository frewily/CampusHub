package io.github.frewily.campushub.controller;

import io.github.frewily.campushub.config.GlobalExceptionHandler;
import io.github.frewily.campushub.config.SecurityConfig;
import io.github.frewily.campushub.exception.BusinessException;
import io.github.frewily.campushub.exception.ErrorCode;
import io.github.frewily.campushub.mapper.AccountAccessMapper;
import io.github.frewily.campushub.security.RedisTokenAuthenticationFilter;
import io.github.frewily.campushub.security.ResourceAuthorizationService;
import io.github.frewily.campushub.security.SecurityErrorResponder;
import io.github.frewily.campushub.service.ReadinessService;
import io.github.frewily.campushub.storage.ImageStorage;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.data.redis.core.HashOperations;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.web.multipart.MaxUploadSizeExceededException;

import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;

import static io.github.frewily.campushub.utils.RedisConstants.LOGIN_USER_KEY;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(classes = DeploymentHttpContractTest.TestApplication.class)
@AutoConfigureMockMvc
@ActiveProfiles("test")
class DeploymentHttpContractTest {

    private static final String TOKEN = "deployment-contract-token";

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private ReadinessService readinessService;
    @MockBean
    private ImageStorage imageStorage;
    @MockBean
    private StringRedisTemplate redisTemplate;
    @MockBean
    private HashOperations<String, Object, Object> hashOperations;
    @MockBean
    private AccountAccessMapper accountAccessMapper;

    @SpringBootConfiguration
    @EnableAutoConfiguration
    @Import({HealthController.class, ImageController.class, UploadController.class,
            GlobalExceptionHandler.class, SecurityConfig.class, SecurityErrorResponder.class,
            RedisTokenAuthenticationFilter.class, ResourceAuthorizationService.class})
    static class TestApplication {
    }

    @Test
    void anonymousCanReadLiveAndReadyWithoutDependencyDetails() throws Exception {
        when(readinessService.ready()).thenReturn(false);

        mockMvc.perform(get("/health/live"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("UP"));

        MvcResult ready = mockMvc.perform(get("/health/ready"))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.status").value("DOWN"))
                .andReturn();
        String body = ready.getResponse().getContentAsString();
        assertFalse(body.contains("mysql"));
        assertFalse(body.contains("redis"));
        assertFalse(body.contains("exception"));
        verify(readinessService).ready();
    }

    @Test
    void anonymousImageReadReturnsMimeAndNosniffHeaders() throws Exception {
        byte[] png = new byte[]{1, 2, 3};
        byte[] jpeg = new byte[]{4, 5, 6};
        when(imageStorage.read("/blogs/1/2/campus.png")).thenReturn(png);
        when(imageStorage.read("/blogs/1/2/campus.jpg")).thenReturn(jpeg);

        mockMvc.perform(get("/imgs/blogs/1/2/campus.png"))
                .andExpect(status().isOk())
                .andExpect(content().contentType(MediaType.IMAGE_PNG))
                .andExpect(header().string("X-Content-Type-Options", "nosniff"))
                .andExpect(content().bytes(png));
        mockMvc.perform(get("/imgs/blogs/1/2/campus.jpg"))
                .andExpect(status().isOk())
                .andExpect(content().contentType(MediaType.IMAGE_JPEG))
                .andExpect(header().string("X-Content-Type-Options", "nosniff"))
                .andExpect(content().bytes(jpeg));
    }

    @Test
    void missingAndUnavailableImagesReturnTypedErrors() throws Exception {
        when(imageStorage.read("/blogs/1/2/missing.png"))
                .thenThrow(new BusinessException(ErrorCode.NOT_FOUND));
        when(imageStorage.read("/blogs/1/2/unavailable.png"))
                .thenThrow(new BusinessException(ErrorCode.IMAGE_STORAGE_UNAVAILABLE));

        mockMvc.perform(get("/imgs/blogs/1/2/missing.png"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.errorCode").value("NOT_FOUND"));
        mockMvc.perform(get("/imgs/blogs/1/2/unavailable.png"))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.errorCode").value("IMAGE_STORAGE_UNAVAILABLE"));
    }

    @Test
    void anonymousUploadIsUnauthorizedWithoutCallingStorage() throws Exception {
        mockMvc.perform(multipart("/upload/blog")
                        .file(new MockMultipartFile("file", "campus.jpg", "image/jpeg", new byte[]{1})))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.errorCode").value("AUTHENTICATION_FAILED"));
        verifyNoInteractions(imageStorage);
    }

    @Test
    void userUploadReturnsTheControlledStoragePath() throws Exception {
        session("USER");
        MockMultipartFile file = new MockMultipartFile("file", "client-name.jpg", "image/jpeg", new byte[]{1});
        when(imageStorage.store(any())).thenReturn("/blogs/3/7/12345678-1234-1234-1234-123456789abc.jpg");

        mockMvc.perform(multipart("/upload/blog").file(file).header("authorization", TOKEN))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data").value("/blogs/3/7/12345678-1234-1234-1234-123456789abc.jpg"));
        verify(imageStorage).store(any());
    }

    @Test
    void adminCanDeleteButUserCannotCallDeleteStorage() throws Exception {
        session("ADMIN");
        mockMvc.perform(get("/upload/blog/delete").param("name", "/blogs/3/7/12345678-1234-1234-1234-123456789abc.jpg")
                        .header("authorization", TOKEN))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true));
        verify(imageStorage).delete("/blogs/3/7/12345678-1234-1234-1234-123456789abc.jpg");
    }

    @Test
    void userDeleteIsForbiddenWithoutCallingStorage() throws Exception {
        session("USER");
        mockMvc.perform(get("/upload/blog/delete").param("name", "/blogs/3/7/image.jpg")
                        .header("authorization", TOKEN))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.errorCode").value("AUTHORIZATION_FAILED"));
        verifyNoInteractions(imageStorage);
    }

    @Test
    void missingFilePartIsTypedBadRequest() throws Exception {
        session("USER");
        mockMvc.perform(multipart("/upload/blog").header("authorization", TOKEN))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.errorCode").value("VALIDATION_FAILED"));
        verifyNoInteractions(imageStorage);
    }

    @Test
    void invalidStoragePathBusinessExceptionIsTypedBadRequest() throws Exception {
        when(imageStorage.read("/blogs/16/1/invalid.png"))
                .thenThrow(new BusinessException(ErrorCode.VALIDATION_FAILED, "图片路径无效"));

        mockMvc.perform(get("/imgs/blogs/16/1/invalid.png"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errorCode").value("VALIDATION_FAILED"));
    }

    @Test
    void oversizedUploadExceptionIsTypedBadRequest() throws Exception {
        session("USER");
        when(imageStorage.store(any())).thenThrow(new MaxUploadSizeExceededException(16L));

        mockMvc.perform(multipart("/upload/blog")
                        .file(new MockMultipartFile("file", "campus.jpg", "image/jpeg", new byte[]{1}))
                        .header("authorization", TOKEN))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.errorCode").value("VALIDATION_FAILED"));
    }

    private void session(String... roles) {
        Long userId = 7L;
        Map<Object, Object> fields = new HashMap<>();
        fields.put("id", userId.toString());
        fields.put("nickName", "synthetic-user");
        when(redisTemplate.opsForHash()).thenReturn(hashOperations);
        when(hashOperations.entries(LOGIN_USER_KEY + TOKEN)).thenReturn(fields);
        when(accountAccessMapper.findAccountStatus(userId)).thenReturn("ACTIVE");
        when(accountAccessMapper.findRoles(userId)).thenReturn(Arrays.asList(roles));
    }
}
