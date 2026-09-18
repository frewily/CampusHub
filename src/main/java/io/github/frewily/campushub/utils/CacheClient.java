package io.github.frewily.campushub.utils;


import cn.hutool.core.util.BooleanUtil;
import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;

import static io.github.frewily.campushub.utils.RedisConstants.*;

@Slf4j
@Component
public class CacheClient {

    private final StringRedisTemplate stringRedisTemplate;

    public CacheClient(StringRedisTemplate stringRedisTemplate) {
        this.stringRedisTemplate = stringRedisTemplate;
    }

    /**
     * 将任意对象序列化后存储到Redis缓存中
     *
     * @param key   Redis缓存的键
     * @param value 要缓存的对象值，会被序列化为JSON字符串
     * @param time  缓存过期时间
     * @param unit  时间单位
     */
    public void set(String key, Object value, Long time, TimeUnit  unit){
        stringRedisTemplate.opsForValue().set(key, JSONUtil.toJsonStr(value), time, unit);
    }

    /**
     * 将对象存储到Redis缓存中，并设置逻辑过期时间
     * 用于实现缓存主动更新策略，避免缓存击穿
     *
     * @param key   Redis缓存的键
     * @param value 要缓存的对象值
     * @param time  逻辑过期时间
     * @param unit  时间单位
     */
    public void setWithLogicalExpire(String key, Object value, Long time, TimeUnit  unit){
        // 逻辑过期时间保存在值中，Redis key 本身不设置 TTL。
        RedisData redisData = new RedisData();
        redisData.setData(value);
        redisData.setExpireTime(LocalDateTime.now().plusSeconds(unit.toSeconds(time)));//unit.toSeconds(time): 将时间单位转换成秒
        stringRedisTemplate.opsForValue().set(key, JSONUtil.toJsonStr(redisData));
    }

    /**
     * 查询缓存，采用缓存空值策略解决缓存穿透问题
     * 如果缓存命中则直接返回，否则查询数据库并将结果写入缓存
     *
     * @param keyPrefix 缓存键前缀
     * @param id        查询ID
     * @param type      返回对象的类型
     * @param dbFallback 数据库查询回调函数
     * @param time      缓存过期时间
     * @param unit      时间单位
     * @param <R>       返回类型
     * @param <ID>      ID类型
     * @return 查询结果，如果缓存和数据库中都不存在则返回null
     */
    public <R ,ID> R queryWithPaaThrough(
            String keyPrefix, ID id, Class<R> type, Function<ID, R> dbFallback, Long time, TimeUnit unit){
        String key = keyPrefix + id;
        String json = stringRedisTemplate.opsForValue().get(key);
        if (StrUtil.isNotBlank(json)) {
            return JSONUtil.toBean(json, type);
        }
        if (json != null) {//-->json为空字符串,直接返回错误信息，不再查询数据库，避免缓存穿透
            return null;

        }
        R r = dbFallback.apply(id);
        if (r == null) {
            stringRedisTemplate.opsForValue().set(key, "", CACHE_NULL_TTL, TimeUnit.MINUTES);
            return null;
        }
        this.set(key, r, time, unit);
        return r;
    }

    private static final ExecutorService CACHE_REBUILD_EXECUTOR = Executors.newFixedThreadPool(10);

    /**
     * 查询缓存，采用逻辑过期策略解决缓存击穿问题
     * 缓存命中后检查逻辑过期时间，如果已过期则通过互斥锁异步重建缓存
     *
     * @param keyPrefix  缓存键前缀
     * @param id         查询ID
     * @param type       返回对象的类型
     * @param dbFallback 数据库查询回调函数
     * @param time       缓存过期时间
     * @param unit       时间单位
     * @param <R>        返回类型
     * @param <ID>       ID类型
     * @return 查询结果，如果缓存不存在则返回null；如果缓存过期则返回旧数据并异步重建
     */
    public <R ,ID> R queryWithLogicalExpire(
            String keyPrefix, ID id, Class<R> type, Function<ID, R> dbFallback, Long time, TimeUnit unit){
        String key = keyPrefix + id;
        String json = stringRedisTemplate.opsForValue().get(key);
        if (StrUtil.isBlank(json)) {
            if (json != null) {
                return null;
            }
            R loaded = dbFallback.apply(id);
            if (loaded == null) {
                stringRedisTemplate.opsForValue().set(key, "", CACHE_NULL_TTL, TimeUnit.MINUTES);
                return null;
            }
            this.setWithLogicalExpire(key, loaded, time, unit);
            return loaded;
        }
        RedisData redisData = JSONUtil.toBean(json, RedisData.class);
        JSONObject data = (JSONObject) redisData.getData();
        R r = JSONUtil.toBean(data, type);
        LocalDateTime expireTime = redisData.getExpireTime();
        if (expireTime.isAfter(LocalDateTime.now())) {
            return r;
        }
        String lockKey = LOCK_SHOP_KEY + id;
        boolean islock = tryLock(lockKey);
        if (islock) {
            CACHE_REBUILD_EXECUTOR.submit(() -> {
                try {
                    R loaded = dbFallback.apply(id);
                    if (loaded == null) {
                        stringRedisTemplate.opsForValue().set(key, "", CACHE_NULL_TTL, TimeUnit.MINUTES);
                    } else {
                        this.setWithLogicalExpire(key, loaded, time, unit);
                    }
                } catch (Exception e) {
                    log.error("重建逻辑过期缓存失败，key={}", key, e);
                } finally {
                    unLock(lockKey);
                }
            });
        }
        return r;
    }

    /**
     * 尝试获取Redis分布式锁
     *
     * @param key 锁的键
     * @return 是否成功获取锁，true表示获取成功，false表示获取失败或异常
     */
    private boolean tryLock(String key) {
        Boolean flag = stringRedisTemplate.opsForValue().setIfAbsent(key, "1", 10L, TimeUnit.SECONDS);
        /**
         * 返回 Boolean（包装类型），有三种可能：
         * true：设置成功（key不存在）
         * false：设置失败（key已存在）
         * null：Redis连接异常或其他错误
         */
        return BooleanUtil.isTrue(flag);// null 时返回 false
    }

    /**
     * 释放Redis分布式锁
     *
     * @param key 锁的键
     */
    private void unLock(String key) {
        stringRedisTemplate.delete(key);
    }

}
