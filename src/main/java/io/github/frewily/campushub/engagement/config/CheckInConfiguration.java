package io.github.frewily.campushub.engagement.config;

import io.github.frewily.campushub.engagement.application.CheckInService;
import io.github.frewily.campushub.engagement.application.CheckInStore;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Clock;

@Configuration
public class CheckInConfiguration {
    @Bean
    public CheckInService checkInService(CheckInStore store) {
        return new CheckInService(store, Clock.systemDefaultZone());
    }
}
