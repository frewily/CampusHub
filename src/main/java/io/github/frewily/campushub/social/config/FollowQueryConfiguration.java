package io.github.frewily.campushub.social.config;

import io.github.frewily.campushub.social.application.FollowLookupRepository;
import io.github.frewily.campushub.social.application.FollowProjectionReader;
import io.github.frewily.campushub.social.application.FollowQueryService;
import io.github.frewily.campushub.social.application.FollowUserReader;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class FollowQueryConfiguration {
    @Bean
    public FollowQueryService followQueryService(FollowLookupRepository repository, FollowProjectionReader projection, FollowUserReader users) {
        return new FollowQueryService(repository, projection, users);
    }
}
