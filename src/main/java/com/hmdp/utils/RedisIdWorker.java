package com.hmdp.utils;


import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import javax.annotation.Resource;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;

@Component
public class RedisIdWorker {

    @Resource
    private StringRedisTemplate stringRedisTemplate;

    // 起始时间戳
    private static final long BEGIN_TIMESTAMP = 1640995200L;

    // 序列号位数
    private static final int COUNT_BITS = 32;

    public Long nextId(String key) {
        //1 生成时间戳
        LocalDateTime now = LocalDateTime.now();
        long nowSecond = now.toEpochSecond(ZoneOffset.UTC);
        long timestamp = nowSecond - BEGIN_TIMESTAMP;
        //2 生成序列号
        //2.1 获取当前日期,精确到天
        String data = now.format(DateTimeFormatter.ofPattern("yyyy:MM:dd"));
        //2.2 自增长
        Long count = stringRedisTemplate.opsForValue().increment("icr:" + key + ":" + data);
        //3 拼接并返回
        return timestamp << COUNT_BITS | count;// << 32: 左移32位（序列号需要的位数），让出低位(全是0)，将时间戳放在序列号前面
                                                // | count: 拼接序列号  |: 按位或，将序列号放在时间戳后面
    }

    // 获取当前时间戳
//    public static void main(String[] args) {
//        LocalDateTime time = LocalDateTime.of(2022, 1, 1, 0, 0, 0);
//        long second = time.toEpochSecond(java.time.ZoneOffset.UTC);
//        System.out.println(second);
//    }
}
