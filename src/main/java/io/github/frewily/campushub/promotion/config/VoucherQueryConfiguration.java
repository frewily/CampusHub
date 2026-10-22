package io.github.frewily.campushub.promotion.config;

import io.github.frewily.campushub.promotion.application.VoucherQueryRepository;
import io.github.frewily.campushub.promotion.application.VoucherQueryService;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class VoucherQueryConfiguration {
    @Bean
    public VoucherQueryService voucherQueryService(VoucherQueryRepository repository) {
        return new VoucherQueryService(repository);
    }
}
