package io.github.frewily.campushub.config;

import io.github.frewily.campushub.service.ReadinessService;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class ObservabilityConfigurationTest {
    private MockEnvironment valid() {
        return new MockEnvironment().withProperty("management.server.address", "127.0.0.1")
                .withProperty("management.server.port", "8082").withProperty("server.port", "8081");
    }
    @Test void listenerMustStayPrivateAndSeparate() {
        assertDoesNotThrow(() -> ObservabilityConfiguration.validateListener(valid()));
        for (String address : new String[]{"0.0.0.0", "localhost", "::", ""}) {
            assertThrows(IllegalStateException.class, () -> ObservabilityConfiguration.validateListener(
                    valid().withProperty("management.server.address", address)));
        }
        assertThrows(IllegalStateException.class, () -> ObservabilityConfiguration.validateListener(
                valid().withProperty("management.server.port", "8081")));
        assertThrows(IllegalStateException.class, () -> ObservabilityConfiguration.validateListener(
                valid().withProperty("management.endpoints.web.base-path", "/")));
    }
    @Test void invalidPortValuesFailClosedAndRandomManagementPortIsAllowed() {
        for (String value : new String[]{"-1", "65536", "bad", ""}) {
            assertThrows(IllegalStateException.class, () -> ObservabilityConfiguration.validateListener(
                    valid().withProperty("management.server.port", value)));
        }
        assertDoesNotThrow(() -> ObservabilityConfiguration.validateListener(
                valid().withProperty("management.server.port", "0").withProperty("server.port", "0")));
    }
    @Test void healthUsesExistingDependencyProbeWithoutDetails() {
        ReadinessService readiness = mock(ReadinessService.class);
        ObservabilityConfiguration configuration = new ObservabilityConfiguration();
        when(readiness.ready()).thenReturn(true);
        assertEquals("UP", configuration.dependenciesHealthIndicator(readiness).health().getStatus().getCode());
        when(readiness.ready()).thenReturn(false);
        assertEquals("DOWN", configuration.dependenciesHealthIndicator(readiness).health().getStatus().getCode());
        assertTrue(configuration.dependenciesHealthIndicator(readiness).health().getDetails().isEmpty());
    }
    @Test void uriSeriesBudgetDoesNotSilentlyGrowForever() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        try {
            registry.config().meterFilter(new ObservabilityConfiguration().httpUriCardinalityLimit());
            for (int i = 0; i < 110; i++) {
                Counter.builder("http.server.requests").tag("uri", "/template-" + i).register(registry).increment();
            }
            assertEquals(100, registry.getMeters().size());
            assertEquals(1, registry.get("http.server.requests").tag("uri", "/template-0").counter().count());
        } finally { registry.close(); }
    }
    @Test void arbitraryHttpMethodsCannotCreateIdentifierTags() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        try {
            registry.config().meterFilter(new ObservabilityConfiguration().safeHttpMethodTags());
            for (int i = 0; i < 20; i++) {
                Counter.builder("http.server.requests").tag("method", "synthetic-private-method-" + i)
                        .register(registry).increment();
            }
            assertEquals(1, registry.getMeters().size());
            assertEquals(20, registry.get("http.server.requests").tag("method", "UNKNOWN").counter().count());
        } finally { registry.close(); }
    }
}
