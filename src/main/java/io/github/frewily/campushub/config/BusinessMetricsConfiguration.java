package io.github.frewily.campushub.config;

import io.github.frewily.campushub.observability.DiagnosticCounters;
import io.github.frewily.campushub.service.OrderCancellationReconciler;
import io.github.frewily.campushub.service.OrderStreamConsumer;
import io.github.frewily.campushub.service.ShopCacheInvalidationService;
import io.github.frewily.campushub.utils.CacheClient;
import io.micrometer.core.instrument.FunctionCounter;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.beans.factory.SmartInitializingSingleton;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.Locale;

/** Export only in-memory fixed-vocabulary observations. A scrape never polls SQL or Redis. */
@Configuration
public class BusinessMetricsConfiguration {
    @Bean
    public SmartInitializingSingleton businessDiagnostics(MeterRegistry registry, CacheClient cache, OrderStreamConsumer orders,
                                          ShopCacheInvalidationService shops, OrderCancellationReconciler cancellations) {
        // MeterBinder is collected while the registry itself is initializing. Resolving Redis-backed
        // services there creates registry -> binder -> Lettuce metrics -> registry recursion.
        return () -> {
            for (CacheClient.DiagnosticEvent event : CacheClient.DiagnosticEvent.values()) {
                FunctionCounter.builder("campushub.shop.cache.events", cache, source -> source.diagnosticCount(event))
                        .tag("outcome", label(event))
                        .description("Process-local cache observations; initial reads, load attempts and unavailable signals")
                        .register(registry);
            }
            bind(registry, "campushub.orders.consumer.events", orders.diagnostics(),
                    OrderStreamConsumer.DiagnosticEvent.class);
            bind(registry, "campushub.shop.invalidation.events", shops.diagnostics(),
                    ShopCacheInvalidationService.DiagnosticEvent.class);
            bind(registry, "campushub.orders.cancellation.events", cancellations.diagnostics(),
                    OrderCancellationReconciler.DiagnosticEvent.class);
        };
    }

    private static <E extends Enum<E>> void bind(MeterRegistry registry, String name,
                                                DiagnosticCounters<E> counters, Class<E> type) {
        for (E event : type.getEnumConstants()) {
            FunctionCounter.builder(name, counters, source -> source.count(event))
                    .tag("outcome", label(event))
                    .description("Process-local confirmed boundary events; not unique business objects or backlog")
                    .register(registry);
        }
    }

    private static String label(Enum<?> event) { return event.name().toLowerCase(Locale.ROOT); }
}
