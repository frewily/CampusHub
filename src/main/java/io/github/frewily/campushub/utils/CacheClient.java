package io.github.frewily.campushub.utils;

import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import io.github.frewily.campushub.exception.BusinessException;
import io.github.frewily.campushub.exception.ErrorCode;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.LongAdder;
import java.util.function.Function;

/** One cache-aside strategy: bounded TTL, token-owned rebuild, epoch-fenced publication. */
@Component
public class CacheClient {
    public static final String DATA_PREFIX = "cache:shop:v2:";
    public static final String EPOCH_PREFIX = "cache:shop:epoch:";
    public static final String LOCK_PREFIX = "lock:shop:v2:";
    private static final DefaultRedisScript<List> READ = script("shop-cache-read.lua", List.class);
    private static final DefaultRedisScript<Long> PUBLISH = script("shop-cache-publish.lua", Long.class);
    private static final DefaultRedisScript<Long> UNLOCK = script("shop-cache-unlock.lua", Long.class);
    private static final DefaultRedisScript<Long> INVALIDATE = script("shop-cache-invalidate.lua", Long.class);
    private final StringRedisTemplate redis;
    private final LongAdder hits = new LongAdder(), negativeHits = new LongAdder(), misses = new LongAdder();
    private final LongAdder loads = new LongAdder(), rejected = new LongAdder(), failures = new LongAdder();

    public CacheClient(StringRedisTemplate redis) { this.redis = redis; }

    public <R> R queryShop(Long id, Class<R> type, Function<Long, R> loader) {
        if (id == null || id <= 0) throw new BusinessException(ErrorCode.VALIDATION_FAILED);
        // A caller's repeatable-read snapshot or uncommitted data must not populate public cache.
        if (TransactionSynchronizationManager.isActualTransactionActive()) throw unavailable();
        try {
            Snapshot initial = read(id);
            if (initial.present) {
                if (initial.empty) negativeHits.increment(); else hits.increment();
                return initial.empty ? null : JSONUtil.toBean(initial.data, type);
            }
            misses.increment();
            long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(250);
            do {
                String token = UUID.randomUUID().toString();
                if (Boolean.TRUE.equals(redis.opsForValue().setIfAbsent(LOCK_PREFIX + id, token, 10, TimeUnit.SECONDS))) {
                    try {
                        Snapshot current = read(id); // Double check after acquiring the lock.
                        if (current.present) return current.empty ? null : JSONUtil.toBean(current.data, type);
                        loads.increment();
                        R loaded = loader.apply(id);
                        JSONObject envelope = new JSONObject();
                        envelope.set("epoch", current.epoch).set("empty", loaded == null).set("data", loaded);
                        long ttl = loaded == null ? negativeTtlMillis() : positiveTtlMillis();
                        Long stored = redis.execute(PUBLISH,
                                Arrays.asList(DATA_PREFIX + id, EPOCH_PREFIX + id, LOCK_PREFIX + id),
                                token, current.epoch, envelope.toString(), Long.toString(ttl));
                        if (Long.valueOf(1).equals(stored)) return loaded;
                        if (stored == null || stored < 0 || stored > 1) throw unavailable();
                        rejected.increment(); // A committed write or expired lease fenced this old load.
                    } finally {
                        try { redis.execute(UNLOCK, Arrays.asList(LOCK_PREFIX + id), token); }
                        catch (RuntimeException ignored) { /* Never delete a successor's bounded lease. */ }
                    }
                }
                pause();
                Snapshot filled = read(id);
                if (filled.present) return filled.empty ? null : JSONUtil.toBean(filled.data, type);
            } while (System.nanoTime() < deadline);
            throw unavailable(); // Bound contention; never fan out hot misses into direct DB fallbacks.
        } catch (BusinessException error) { throw error; }
        catch (RuntimeException error) { throw unavailable(); }
    }

    /** Only invoke for a DB-committed event. A new epoch on EVERY attempt avoids ABA. */
    public void invalidateShop(Long id) {
        Long result = redis.execute(INVALIDATE,
                Arrays.asList(DATA_PREFIX + id, EPOCH_PREFIX + id, RedisConstants.CACHE_SHOP_KEY + id),
                UUID.randomUUID().toString());
        if (!Long.valueOf(1).equals(result)) throw unavailable();
    }

    private Snapshot read(Long id) {
        List<?> result = redis.execute(READ, Arrays.asList(DATA_PREFIX + id, EPOCH_PREFIX + id));
        if (result == null || result.size() != 2 || !(result.get(0) instanceof String)
                || !(result.get(1) instanceof String)) throw unavailable();
        String epoch = (String) result.get(0), raw = (String) result.get(1);
        if (raw.isEmpty()) return new Snapshot(epoch, false, false, null);
        JSONObject entry = JSONUtil.parseObj(raw);
        if (!epoch.equals(entry.getStr("epoch"))) return new Snapshot(epoch, false, false, null);
        Object empty = entry.get("empty");
        if (!(empty instanceof Boolean)) throw unavailable();
        if (Boolean.TRUE.equals(empty)) return new Snapshot(epoch, true, true, null);
        JSONObject data = entry.getJSONObject("data");
        if (data == null) throw unavailable();
        return new Snapshot(epoch, true, false, data);
    }

    public static long positiveTtlMillis() { return 60000 + ThreadLocalRandom.current().nextLong(15001); }
    public static long negativeTtlMillis() { return 10000 + ThreadLocalRandom.current().nextLong(5001); }
    /** Process-local diagnostics, not a metrics endpoint or a measured hit ratio. */
    public long[] statistics() {
        return new long[]{hits.sum(), negativeHits.sum(), misses.sum(), loads.sum(), rejected.sum(), failures.sum()};
    }
    private BusinessException unavailable() {
        failures.increment(); return new BusinessException(ErrorCode.SHOP_STATE_UNAVAILABLE);
    }
    private void pause() {
        try { Thread.sleep(10); }
        catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); throw unavailable(); }
    }
    private static <T> DefaultRedisScript<T> script(String name, Class<T> type) {
        DefaultRedisScript<T> script = new DefaultRedisScript<>();
        script.setLocation(new ClassPathResource(name)); script.setResultType(type); return script;
    }
    private static class Snapshot {
        final String epoch; final boolean present, empty; final JSONObject data;
        Snapshot(String epoch, boolean present, boolean empty, JSONObject data) {
            this.epoch = epoch; this.present = present; this.empty = empty; this.data = data;
        }
    }
}
