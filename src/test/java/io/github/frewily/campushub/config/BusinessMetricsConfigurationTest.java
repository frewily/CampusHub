package io.github.frewily.campushub.config;

import io.github.frewily.campushub.observability.DiagnosticCounters;
import io.github.frewily.campushub.service.*;
import io.github.frewily.campushub.utils.CacheClient;
import io.micrometer.core.instrument.FunctionCounter;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.micrometer.prometheus.PrometheusConfig;
import io.micrometer.prometheus.PrometheusMeterRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.autoconfigure.data.redis.RedisAutoConfiguration;
import org.springframework.boot.actuate.autoconfigure.metrics.MetricsAutoConfiguration;
import org.springframework.boot.actuate.autoconfigure.metrics.export.prometheus.PrometheusMetricsExportAutoConfiguration;
import org.springframework.boot.actuate.autoconfigure.metrics.redis.LettuceMetricsAutoConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.*;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class BusinessMetricsConfigurationTest {
    private final StringRedisTemplate redis = mock(StringRedisTemplate.class);
    private final CacheClient cache = new CacheClient(redis);
    private final OrderStreamConsumer orders = mock(OrderStreamConsumer.class);
    private final ShopCacheInvalidationService shops = mock(ShopCacheInvalidationService.class);
    private final OrderCancellationReconciler cancellations = mock(OrderCancellationReconciler.class);

    private void setupCounters() {
        when(orders.diagnostics()).thenReturn(new DiagnosticCounters<>(OrderStreamConsumer.DiagnosticEvent.class));
        when(shops.diagnostics()).thenReturn(new DiagnosticCounters<>(ShopCacheInvalidationService.DiagnosticEvent.class));
        when(cancellations.diagnostics()).thenReturn(new DiagnosticCounters<>(OrderCancellationReconciler.DiagnosticEvent.class));
    }

    @Configuration(proxyBeanMethods = false)
    static class StartupFixture {
        @Bean CacheClient cacheClient(StringRedisTemplate redis) { return new CacheClient(redis); }
        @Bean OrderStreamConsumer orders() {
            OrderStreamConsumer service = mock(OrderStreamConsumer.class);
            when(service.diagnostics()).thenReturn(new DiagnosticCounters<>(OrderStreamConsumer.DiagnosticEvent.class));
            return service;
        }
        @Bean ShopCacheInvalidationService shops() {
            ShopCacheInvalidationService service = mock(ShopCacheInvalidationService.class);
            when(service.diagnostics()).thenReturn(new DiagnosticCounters<>(ShopCacheInvalidationService.DiagnosticEvent.class));
            return service;
        }
        @Bean OrderCancellationReconciler cancellations() {
            OrderCancellationReconciler service = mock(OrderCancellationReconciler.class);
            when(service.diagnostics()).thenReturn(new DiagnosticCounters<>(OrderCancellationReconciler.DiagnosticEvent.class));
            return service;
        }
    }

    @Test void actualLettuceMetricsAndRegistryInitializeWithoutCircularReferencesOrARedisServer() {
        new ApplicationContextRunner().withAllowCircularReferences(false)
                .withConfiguration(AutoConfigurations.of(MetricsAutoConfiguration.class,
                        PrometheusMetricsExportAutoConfiguration.class, RedisAutoConfiguration.class, LettuceMetricsAutoConfiguration.class))
                .withUserConfiguration(BusinessMetricsConfiguration.class, StartupFixture.class)
                .run(context -> {
                    assertNull(context.getStartupFailure());
                    assertNotNull(context.getBean(StringRedisTemplate.class));
                    assertNotNull(context.getBean("lettuceMetrics"));
                    assertTrue(context.getBean(PrometheusMeterRegistry.class).scrape().contains("campushub_shop_cache_events_total"));
                });
    }

    @Test void fixedSeriesAreZeroInitiallyAndScrapesNeverCallDependencies() {
        setupCounters();
        PrometheusMeterRegistry registry = new PrometheusMeterRegistry(PrometheusConfig.DEFAULT);
        try {
            new BusinessMetricsConfiguration().businessDiagnostics(registry, cache, orders, shops, cancellations).afterSingletonsInstantiated();
            int expected = CacheClient.DiagnosticEvent.values().length + OrderStreamConsumer.DiagnosticEvent.values().length
                    + ShopCacheInvalidationService.DiagnosticEvent.values().length + OrderCancellationReconciler.DiagnosticEvent.values().length;
            assertEquals(expected, registry.getMeters().size());
            Set<String> names = new HashSet<>(Arrays.asList("campushub.shop.cache.events", "campushub.orders.consumer.events",
                    "campushub.shop.invalidation.events", "campushub.orders.cancellation.events"));
            registry.getMeters().forEach(meter -> {
                assertTrue(names.contains(meter.getId().getName()));
                assertEquals(1, meter.getId().getTags().size());
                assertEquals("outcome", meter.getId().getTags().get(0).getKey());
                assertEquals(0, ((FunctionCounter) meter).count());
            });
            assertTrue(registry.scrape().contains("campushub_shop_cache_events_total"));
            registry.scrape();
            verifyNoInteractions(redis);
        } finally { registry.close(); }
    }

    @Test void countersReadLiveEventsAndResetWithANewSource() {
        setupCounters();
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        try {
            new BusinessMetricsConfiguration().businessDiagnostics(registry, cache, orders, shops, cancellations).afterSingletonsInstantiated();
            orders.diagnostics().increment(OrderStreamConsumer.DiagnosticEvent.HANDLER_RETURNED);
            assertEquals(1, registry.get("campushub.orders.consumer.events").tag("outcome", "handler_returned").functionCounter().count());
            assertEquals(0, registry.get("campushub.orders.consumer.events").tag("outcome", "acknowledged").functionCounter().count());
            assertEquals(0, new DiagnosticCounters<>(OrderStreamConsumer.DiagnosticEvent.class).count(OrderStreamConsumer.DiagnosticEvent.HANDLER_RETURNED));
        } finally { registry.close(); }
    }

    @Test void concurrentIncrementsAreNotLostAfterWorkersFinish() throws Exception {
        DiagnosticCounters<OrderStreamConsumer.DiagnosticEvent> counters = new DiagnosticCounters<>(OrderStreamConsumer.DiagnosticEvent.class);
        ExecutorService pool = Executors.newFixedThreadPool(4);
        try {
            java.util.List<Future<?>> work = new java.util.ArrayList<>();
            for (int i = 0; i < 4; i++) work.add(pool.submit(() -> {
                for (int j = 0; j < 1000; j++) counters.increment(OrderStreamConsumer.DiagnosticEvent.HANDLER_RETURNED);
            }));
            for (Future<?> item : work) item.get(5, TimeUnit.SECONDS);
            assertEquals(4000, counters.count(OrderStreamConsumer.DiagnosticEvent.HANDLER_RETURNED));
        } finally { pool.shutdownNow(); }
    }
}
