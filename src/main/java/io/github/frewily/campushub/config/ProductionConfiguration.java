package io.github.frewily.campushub.config;

import org.springframework.beans.factory.config.BeanFactoryPostProcessor;
import org.springframework.context.annotation.*;
import org.springframework.core.env.Environment;
import java.nio.file.Paths;
import java.net.URI;
import java.net.URLDecoder;
import java.util.*;

/** Reject missing/demo production credentials before creating network clients. Never print their values. */
@Configuration
@Profile("prod")
public class ProductionConfiguration {
    @Bean
    public static BeanFactoryPostProcessor productionGuard(Environment environment) {
        return factory -> validate(environment);
    }
    public static void validate(Environment environment) {
        String url = required(environment, "spring.datasource.url");
        if (!utcUrl(url)) {
            throw new IllegalStateException("prod requires a MySQL URL with the documented UTC parameters");
        }
        if ("root".equalsIgnoreCase(required(environment, "spring.datasource.username"))) {
            throw new IllegalStateException("prod requires a dedicated database user");
        }
        required(environment, "spring.datasource.password");
        required(environment, "spring.redis.host");
        String port = required(environment, "spring.redis.port");
        try {
            int number = Integer.parseInt(port);
            if (number < 1 || number > 65535) throw new NumberFormatException();
        } catch (NumberFormatException invalid) {
            throw new IllegalStateException("prod requires a valid Redis port");
        }
        required(environment, "spring.redis.password");
        String directory = required(environment, "campushub.images.directory");
        if (!Paths.get(directory).isAbsolute() || Paths.get(directory).normalize().getNameCount() == 0) {
            throw new IllegalStateException("prod requires an absolute non-root image directory");
        }
    }
    private static boolean utcUrl(String url) {
        try {
            if (!url.startsWith("jdbc:mysql://")) return false;
            String query = new URI(url.substring(5)).getRawQuery();
            if (query == null) return false;
            Map<String,String> parameters = new HashMap<>();
            for (String part : query.split("&")) {
                String[] pair = part.split("=", 2);
                if (pair.length != 2) return false;
                String key = URLDecoder.decode(pair[0], "UTF-8");
                if (parameters.put(key, URLDecoder.decode(pair[1], "UTF-8")) != null) return false;
            }
            return "UTC".equals(parameters.get("serverTimezone")) && "true".equals(parameters.get("forceConnectionTimeZoneToSession"));
        } catch (Exception invalid) { return false; }
    }
    private static String required(Environment environment, String key) {
        String value;
        try { value = environment.getProperty(key); }
        catch (IllegalArgumentException missing) { throw new IllegalStateException("prod requires " + key); }
        if (value == null || value.trim().isEmpty() || value.startsWith("change-me") || value.contains("${")) {
            throw new IllegalStateException("prod requires a non-demo value for " + key);
        }
        return value;
    }
}
