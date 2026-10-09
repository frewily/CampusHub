package io.github.frewily.campushub.observability;

import io.github.frewily.campushub.config.GlobalExceptionHandler;
import io.github.frewily.campushub.config.ObservabilityConfiguration;
import io.github.frewily.campushub.config.SecurityConfig;
import io.github.frewily.campushub.controller.HealthController;
import io.github.frewily.campushub.exception.BusinessException;
import io.github.frewily.campushub.exception.ErrorCode;
import io.github.frewily.campushub.mapper.AccountAccessMapper;
import io.github.frewily.campushub.security.RedisTokenAuthenticationFilter;
import io.github.frewily.campushub.security.SecurityErrorResponder;
import io.github.frewily.campushub.service.ReadinessService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.boot.test.autoconfigure.actuate.metrics.AutoConfigureMetrics;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.web.server.LocalServerPort;
import org.springframework.boot.actuate.autoconfigure.web.server.LocalManagementPort;
import org.springframework.context.annotation.Import;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.*;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.web.bind.annotation.*;
import java.util.UUID;
import java.util.Arrays;
import java.util.stream.Collectors;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/** Real private HTTP listeners, mocked business dependencies; not a real-DB acceptance test. */
@SpringBootTest(classes = ObservabilityHttpContractTest.TestApplication.class,
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {"management.server.port=0", "server.address=127.0.0.1"})
@ActiveProfiles("test")
@AutoConfigureMetrics
class ObservabilityHttpContractTest {
    @LocalServerPort int businessPort;
    @LocalManagementPort int managementPort;
    @Autowired TestRestTemplate http;
    @MockBean ReadinessService readiness;
    @MockBean StringRedisTemplate redis;
    @MockBean AccountAccessMapper accounts;

    @SpringBootConfiguration
    @EnableAutoConfiguration
    @Import({SecurityConfig.class, SecurityErrorResponder.class, RedisTokenAuthenticationFilter.class,
            ObservabilityConfiguration.class, HealthController.class, GlobalExceptionHandler.class, ProbeController.class})
    static class TestApplication { }

    @RestController
    static class ProbeController {
        @GetMapping("/shop/trace-probe/{id}")
        String probe(@PathVariable String id) { return "ok"; }
        @GetMapping("/shop/trace-failure")
        String failure() { throw new BusinessException(ErrorCode.VALIDATION_FAILED); }
    }
    @BeforeEach void ready() { when(readiness.ready()).thenReturn(true); }
    private String management(String path) { return "http://127.0.0.1:" + managementPort + path; }

    @Test void managementIsSeparateAndBusinessSocketDoesNotServeAnonymousMetrics() {
        assertNotEquals(businessPort, managementPort);
        assertEquals(401, http.getForEntity("/actuator/prometheus", String.class).getStatusCodeValue());
        assertEquals(401, http.getForEntity("/actuator/env", String.class).getStatusCodeValue());
        ResponseEntity<String> metrics = http.getForEntity(management("/actuator/prometheus"), String.class);
        assertEquals(200, metrics.getStatusCodeValue());
        assertTrue(metrics.getBody().contains("jvm_memory_used_bytes"));
        verifyNoInteractions(redis, accounts);
    }
    @Test void healthOnlyReturnsStatusAndTracksTheExistingProbe() {
        ResponseEntity<String> up = http.getForEntity(management("/actuator/health"), String.class);
        assertEquals(200, up.getStatusCodeValue()); assertEquals("{\"status\":\"UP\"}", up.getBody());
        when(readiness.ready()).thenReturn(false);
        ResponseEntity<String> down = http.getForEntity(management("/actuator/health"), String.class);
        assertEquals(503, down.getStatusCodeValue()); assertEquals("{\"status\":\"DOWN\"}", down.getBody());
        assertEquals(200, http.getForEntity("/health/live", String.class).getStatusCodeValue());
    }
    @Test void configurationAndMutationEndpointsAreNotExposed() {
        for (String endpoint : new String[]{"env", "configprops", "beans", "heapdump", "loggers", "shutdown", "metrics"}) {
            assertNotEquals(200, http.getForEntity(management("/actuator/" + endpoint), String.class).getStatusCodeValue());
        }
        for (String businessRoute : new String[]{"/shop/trace-probe/123", "/shop/search", "/health/live"}) {
            assertNotEquals(200, http.getForEntity(management(businessRoute), String.class).getStatusCodeValue(),
                    "management must not serve business routes");
        }
        assertNotEquals(200, http.postForEntity(management("/actuator/prometheus"), "", String.class).getStatusCodeValue());
    }
    @Test void metricsUseRouteTemplatesWithoutClientIdentifiersAndExposeHistogramBuckets() {
        String marker = "synthetic-query-marker";
        ResponseEntity<String> first = http.getForEntity("/shop/trace-probe/123?keyword=" + marker, String.class);
        ResponseEntity<String> second = http.getForEntity("/shop/trace-probe/456", String.class);
        assertEquals(200, first.getStatusCodeValue()); assertEquals(200, second.getStatusCodeValue());
        String requestId = first.getHeaders().getFirst("X-Request-Id");
        assertNotNull(requestId); UUID.fromString(requestId);
        String metrics = http.getForObject(management("/actuator/prometheus"), String.class);
        assertTrue(metrics.contains("http_server_requests_seconds_count"));
        assertTrue(metrics.contains("http_server_requests_seconds_bucket"));
        assertFalse(metrics.contains("http_client_requests_seconds"));
        assertTrue(metrics.contains("uri=\"/shop/trace-probe/{id}\""));
        assertFalse(metrics.contains(marker), "query marker in metric families: " + familiesContaining(metrics, marker));
        assertFalse(metrics.contains(requestId), "request ID in metric families: " + familiesContaining(metrics, requestId));
        assertFalse(metrics.contains("/trace-probe/123")); assertFalse(metrics.contains("/trace-probe/456"));
    }
    private String familiesContaining(String metrics, String marker) {
        return Arrays.stream(metrics.split("\n")).filter(line -> line.contains(marker))
                .map(line -> line.split("[ {]", 2)[0]).distinct().collect(Collectors.joining(","));
    }
    @Test void requestIdAppearsOnSecurityAndValidationErrorsWithoutTrustingClientInput() {
        HttpHeaders headers = new HttpHeaders(); headers.set("X-Request-Id", "untrusted-client-id");
        ResponseEntity<String> unauthorized = http.exchange("/upload/blog/delete?name=synthetic", HttpMethod.GET,
                new HttpEntity<>(headers), String.class);
        assertEquals(401, unauthorized.getStatusCodeValue());
        UUID.fromString(unauthorized.getHeaders().getFirst("X-Request-Id"));
        assertNotEquals("untrusted-client-id", unauthorized.getHeaders().getFirst("X-Request-Id"));
        ResponseEntity<String> invalid = http.getForEntity("/shop/trace-failure", String.class);
        assertEquals(400, invalid.getStatusCodeValue()); UUID.fromString(invalid.getHeaders().getFirst("X-Request-Id"));
    }
}
