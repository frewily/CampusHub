package io.github.frewily.campushub.config;


import org.redisson.Redisson;
import org.redisson.api.RedissonClient;
import org.redisson.config.Config;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.boot.autoconfigure.data.redis.RedisProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;


@Configuration
@EnableConfigurationProperties(RedisProperties.class)
public class RedissonConfig {

    @Bean
    public RedissonClient redissonClient(RedisProperties properties){
        Config config = new Config();
        config.useSingleServer().setAddress((properties.isSsl() ? "rediss://" : "redis://")
                + properties.getHost() + ":" + properties.getPort())
                .setDatabase(properties.getDatabase()).setConnectTimeout(2000).setTimeout(2000);
        if (properties.getPassword() != null && !properties.getPassword().isEmpty()) {
            config.useSingleServer().setPassword(properties.getPassword());
        }
        if (properties.getUsername() != null && !properties.getUsername().isEmpty()) {
            config.useSingleServer().setUsername(properties.getUsername());
        }
        return Redisson.create(config);
    }
}
