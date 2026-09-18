package com.hmdp.config;


import org.redisson.Redisson;
import org.redisson.api.RedissonClient;
import org.redisson.config.Config;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;


//创建RedissonClient对象
@Configuration
public class RedissonConfig {

    @Bean
    public RedissonClient redissonClient(){
        //配置
        Config config = new Config();
        String redisHost = System.getenv().getOrDefault("REDIS_HOST", "127.0.0.1");
        String redisPort = System.getenv().getOrDefault("REDIS_PORT", "6379");
        String redisPassword = System.getenv().getOrDefault("REDIS_PASSWORD", "");
        config.useSingleServer().setAddress("redis://" + redisHost + ":" + redisPort);
        if (!redisPassword.isEmpty()) {
            config.useSingleServer().setPassword(redisPassword);
        }
        //创建redissonClient对象
        return Redisson.create(config);
    }
}
