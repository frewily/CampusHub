package io.github.frewily.campushub.config;

import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;
import static org.junit.jupiter.api.Assertions.*;

class ProductionConfigurationTest {
    private MockEnvironment valid() {
        return new MockEnvironment().withProperty("spring.datasource.url", "jdbc:mysql://mysql:3306/campushub?serverTimezone=UTC&forceConnectionTimeZoneToSession=true")
                .withProperty("spring.datasource.username", "campushub").withProperty("spring.datasource.password", "synthetic-only-db-password")
                .withProperty("spring.redis.host", "redis").withProperty("spring.redis.port", "6379")
                .withProperty("spring.redis.password", "synthetic-only-redis-password").withProperty("campushub.images.directory", "/app/data/uploads");
    }
    @Test void explicitProductionValuesPassWithoutOpeningConnections() { assertDoesNotThrow(() -> ProductionConfiguration.validate(valid())); }
    @Test void requiredPropertiesNeverFallBackToDemoCredentials() {
        for (String key : new String[]{"spring.datasource.url", "spring.datasource.username", "spring.datasource.password",
                "spring.redis.host", "spring.redis.port", "spring.redis.password", "campushub.images.directory"}) {
            MockEnvironment environment = valid(); environment.setProperty(key, "");
            assertThrows(IllegalStateException.class, () -> ProductionConfiguration.validate(environment), key);
            environment.setProperty(key, "change-me-private-test");
            IllegalStateException error = assertThrows(IllegalStateException.class, () -> ProductionConfiguration.validate(environment));
            assertFalse(error.getMessage().contains("private-test"));
        }
    }
    @Test void unsafeDatabaseAndRedisDefaultsAreRejected() {
        MockEnvironment environment = valid(); environment.setProperty("spring.datasource.username", "root");
        assertThrows(IllegalStateException.class, () -> ProductionConfiguration.validate(environment));
        MockEnvironment url = valid().withProperty("spring.datasource.url", "jdbc:mysql://mysql:3306/campushub");
        assertThrows(IllegalStateException.class, () -> ProductionConfiguration.validate(url));
        for (String suffix : new String[]{"serverTimezone=UTCx&forceConnectionTimeZoneToSession=true",
                "serverTimezone=UTC&forceConnectionTimeZoneToSession=true&forceConnectionTimeZoneToSession=false"}) {
            MockEnvironment ambiguous = valid().withProperty("spring.datasource.url", "jdbc:mysql://mysql:3306/campushub?" + suffix);
            assertThrows(IllegalStateException.class, () -> ProductionConfiguration.validate(ambiguous));
        }
        for (String port : new String[]{"0", "65536", "not-a-port"}) {
            MockEnvironment redis = valid().withProperty("spring.redis.port", port);
            assertThrows(IllegalStateException.class, () -> ProductionConfiguration.validate(redis));
        }
    }
    @Test void relativeOrRootUploadDirectoryIsRejected() {
        for (String directory : new String[]{"./data/uploads", "/", "/app/.."}) {
            MockEnvironment environment = valid().withProperty("campushub.images.directory", directory);
            assertThrows(IllegalStateException.class, () -> ProductionConfiguration.validate(environment));
        }
    }
}
