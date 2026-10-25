package io.github.frewily.campushub.identity.config;

import io.github.frewily.campushub.identity.application.SessionLogoutService;
import io.github.frewily.campushub.identity.application.SessionTerminator;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class SessionLogoutConfiguration {
    @Bean
    public SessionLogoutService sessionLogoutService(SessionTerminator sessions) {
        return new SessionLogoutService(sessions);
    }
}
