package io.github.frewily.campushub.social.config;

import io.github.frewily.campushub.social.application.FollowCommandRepository;
import io.github.frewily.campushub.social.application.FollowCommandService;
import io.github.frewily.campushub.social.application.FollowProjectionWriter;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import java.time.Clock;

@Configuration
public class FollowCommandConfiguration {
    @Bean
    public FollowCommandService followCommandService(FollowCommandRepository repository, FollowProjectionWriter projection) {
        return new FollowCommandService(repository, projection, Clock.systemUTC());
    }
}
