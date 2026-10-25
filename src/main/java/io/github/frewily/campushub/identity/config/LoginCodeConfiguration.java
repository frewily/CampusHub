package io.github.frewily.campushub.identity.config;

import io.github.frewily.campushub.identity.application.LoginCodeGenerator;
import io.github.frewily.campushub.identity.application.LoginCodeService;
import io.github.frewily.campushub.identity.application.LoginCodeStore;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class LoginCodeConfiguration {
    @Bean
    public LoginCodeService loginCodeService(LoginCodeStore store, LoginCodeGenerator generator) {
        return new LoginCodeService(store, generator);
    }
}
