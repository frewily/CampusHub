package io.github.frewily.campushub.identity.config;

import io.github.frewily.campushub.identity.application.UserProfileQueryService;
import io.github.frewily.campushub.identity.application.UserProfileReader;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class UserProfileQueryConfiguration {
    @Bean
    public UserProfileQueryService userProfileQueryService(UserProfileReader reader) {
        return new UserProfileQueryService(reader);
    }
}
