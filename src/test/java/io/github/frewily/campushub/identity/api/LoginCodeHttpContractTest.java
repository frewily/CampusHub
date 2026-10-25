package io.github.frewily.campushub.identity.api;

import io.github.frewily.campushub.config.GlobalExceptionHandler;
import io.github.frewily.campushub.config.SecurityConfig;
import io.github.frewily.campushub.mapper.AccountAccessMapper;
import io.github.frewily.campushub.security.RedisTokenAuthenticationFilter;
import io.github.frewily.campushub.security.SecurityErrorResponder;
import io.github.frewily.campushub.identity.application.LoginCodeService;
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

import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(classes = LoginCodeHttpContractTest.TestApplication.class)
@AutoConfigureMockMvc
class LoginCodeHttpContractTest {
    @Autowired private MockMvc mvc;
    @MockBean private LoginCodeService service;
    @MockBean private StringRedisTemplate redis;
    @MockBean private AccountAccessMapper accounts;

    @SpringBootConfiguration
    @EnableAutoConfiguration
    @Import({LoginCodeController.class, GlobalExceptionHandler.class, SecurityConfig.class,
            SecurityErrorResponder.class, RedisTokenAuthenticationFilter.class})
    static class TestApplication {}

    @Test void successfulResponseIsExactAndNeverContainsCode() throws Exception {
        mvc.perform(post("/user/code").param("phone", "13800138000"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data").value("发送验证码成功"))
                .andExpect(content().json("{\"success\":true,\"data\":\"发送验证码成功\"}", true))
                .andExpect(jsonPath("$.code").doesNotExist());
        verify(service).sendCode("13800138000");
    }

    @Test void requestValidationRejectsMissingBlankAndMalformedPhone() throws Exception {
        mvc.perform(post("/user/code")).andExpect(status().isBadRequest());
        mvc.perform(post("/user/code").param("phone", "   ")).andExpect(status().isBadRequest());
        mvc.perform(post("/user/code").param("phone", "bad")).andExpect(status().isBadRequest());
        verifyNoInteractions(service);
    }
}
