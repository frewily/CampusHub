package io.github.frewily.campushub.config;

import io.github.frewily.campushub.observability.RequestTraceFilter;
import io.github.frewily.campushub.service.ReadinessService;
import io.micrometer.core.instrument.config.MeterFilter;
import io.micrometer.core.instrument.Meter;
import io.micrometer.core.instrument.Tag;
import org.springframework.beans.factory.config.BeanFactoryPostProcessor;
import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.HealthIndicator;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;
import org.springframework.core.env.Environment;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;

/** A private, separate listener: never silently put anonymous metrics on the business socket. */
@Configuration
public class ObservabilityConfiguration {
    private static final Set<String> HTTP_METHODS = new HashSet<>(Arrays.asList(
            "GET", "POST", "PUT", "PATCH", "DELETE", "HEAD", "OPTIONS", "UNKNOWN"));
    @Bean
    public static BeanFactoryPostProcessor managementListenerGuard(Environment environment) {
        return factory -> validateListener(environment);
    }

    public static void validateListener(Environment environment) {
        if (!"127.0.0.1".equals(environment.getProperty("management.server.address"))) {
            throw new IllegalStateException("management requires the IPv4 loopback address");
        }
        int management = port(environment, "management.server.port");
        int business = port(environment, "server.port");
        if (management != 0 && management == business) {
            throw new IllegalStateException("management requires a separate port");
        }
        if (!"/actuator".equals(environment.getProperty("management.endpoints.web.base-path", "/actuator"))) {
            throw new IllegalStateException("management requires the documented actuator base path");
        }
    }

    private static int port(Environment environment, String key) {
        try {
            int value = Integer.parseInt(environment.getRequiredProperty(key));
            if (value < 0 || value > 65535) throw new IllegalArgumentException();
            return value;
        } catch (IllegalArgumentException invalid) {
            throw new IllegalStateException("invalid port configuration for " + key);
        }
    }

    @Bean
    public HealthIndicator dependenciesHealthIndicator(ReadinessService readiness) {
        return () -> readiness.ready() ? Health.up().build() : Health.down().build();
    }

    @Bean
    public MeterFilter safeHttpMethodTags() {
        return new MeterFilter() {
            @Override public Meter.Id map(Meter.Id id) {
                String method = id.getTag("method");
                if (id.getName().startsWith("http.server.requests") && method != null && !HTTP_METHODS.contains(method)) {
                    return id.withTag(Tag.of("method", "UNKNOWN"));
                }
                return id;
            }
        };
    }

    @Bean
    public MeterFilter httpUriCardinalityLimit() {
        return MeterFilter.maximumAllowableTags("http.server.requests", "uri", 100, MeterFilter.deny());
    }

    @Bean
    public FilterRegistrationBean<RequestTraceFilter> requestTraceRegistration() {
        FilterRegistrationBean<RequestTraceFilter> registration = new FilterRegistrationBean<>(new RequestTraceFilter());
        registration.setOrder(Ordered.HIGHEST_PRECEDENCE + 10);
        registration.addUrlPatterns("/*");
        return registration;
    }
}
