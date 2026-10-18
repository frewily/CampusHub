package io.github.frewily.campushub.merchant.config;

import io.github.frewily.campushub.merchant.application.ShopTypeCache;
import io.github.frewily.campushub.merchant.application.ShopTypeQueryService;
import io.github.frewily.campushub.merchant.application.ShopTypeRepository;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class ShopTypeConfiguration {
    @Bean
    public ShopTypeQueryService shopTypeQueryService(ShopTypeRepository repository, ShopTypeCache cache) {
        return new ShopTypeQueryService(repository, cache);
    }
}
