package io.github.frewily.campushub.service;

import org.springframework.core.io.ClassPathResource;
import org.springframework.dao.DataAccessException;
import org.springframework.data.domain.Range;
import org.springframework.data.redis.connection.stream.*;
import org.springframework.data.redis.core.RedisCallback;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;

import java.math.BigInteger;
import java.time.Duration;
import java.util.*;

/** Redis 6-compatible bounded recovery and owner-checked state transitions. Single Redis only. */
public class OrderStreamQueue {
    private static final int PAGE_SIZE = 32;
    private static final DefaultRedisScript<Long> TRANSITION = new DefaultRedisScript<>();
    static {
        TRANSITION.setLocation(new ClassPathResource("order-stream-transition.lua"));
        TRANSITION.setResultType(Long.class);
    }
    private final StringRedisTemplate redis;
    private final String stream;
    private final String group;
    private final String consumer;
    private final Duration claimIdle;
    private final int maxAttempts;
    private String pendingCursor = "0-0";

    public OrderStreamQueue(StringRedisTemplate redis, String stream, String group, String consumer,
                            Duration claimIdle, int maxAttempts) {
        if (claimIdle.isNegative() || maxAttempts < 1 || maxAttempts > 100) {
            throw new IllegalArgumentException("Invalid order retry policy");
        }
        this.redis = redis;
        this.stream = stream;
        this.group = group;
        this.consumer = consumer;
        this.claimIdle = claimIdle;
        this.maxAttempts = maxAttempts;
    }

    public void initialize() {
        byte[] key = redis.getStringSerializer().serialize(stream);
        try {
            redis.execute((RedisCallback<String>) connection -> connection.streamCommands()
                    .xGroupCreate(key, group, ReadOffset.from("0-0"), true));
        } catch (DataAccessException error) {
            for (Throwable cause = error; cause != null; cause = cause.getCause()) {
                if (cause.getMessage() != null && cause.getMessage().contains("BUSYGROUP")) return;
            }
            throw error;
        }
    }

    public List<MapRecord<String, Object, Object>> recoverPending() {
        PendingMessages page = redis.opsForStream().pending(stream, group,
                Range.rightUnbounded(Range.Bound.inclusive(pendingCursor)), PAGE_SIZE);
        List<MapRecord<String, Object, Object>> recovered = new ArrayList<>();
        for (PendingMessage message : page) {
            // Redis rechecks idle time atomically; another claimant may have beaten this snapshot.
            if (message.getElapsedTimeSinceLastDelivery().compareTo(claimIdle) >= 0) {
                List<MapRecord<String, Object, Object>> records = redis.opsForStream().claim(
                        stream, group, consumer, claimIdle, message.getId());
                if (records != null) recovered.addAll(records);
            }
        }
        // Advance past hot/non-idle entries too: a busy first page cannot hide old later messages.
        pendingCursor = page.size() < PAGE_SIZE ? "0-0" : nextId(page.get(page.size() - 1).getIdAsString());
        return recovered;
    }

    public List<MapRecord<String, Object, Object>> readNew(Duration block) {
        StreamReadOptions options = StreamReadOptions.empty().count(1);
        if (!block.isZero()) options = options.block(block);
        List<MapRecord<String, Object, Object>> records = redis.opsForStream().read(
                Consumer.from(group, consumer), options, StreamOffset.create(stream, ReadOffset.lastConsumed()));
        return records == null ? Collections.emptyList() : records;
    }

    /** -1: no longer owner, 0: budget exhausted, positive: attempt reserved before DB work. */
    public long begin(RecordId id) { return transition("BEGIN", id, ""); }
    public long success(RecordId id) { return transition("SUCCESS", id, ""); }
    /** 1: archived then ACKed; 0: still pending; -1: stale worker. */
    public long failure(RecordId id, String errorClass) { return transition("FAILURE", id, errorClass); }

    private long transition(String action, RecordId id, String errorClass) {
        Long result = redis.execute(TRANSITION, Arrays.asList(stream, stream + ".attempts",
                stream + ".dead", stream + ".failures"), action, group, consumer, id.getValue(),
                String.valueOf(maxAttempts), errorClass);
        if (result == null) throw new IllegalStateException("Order transition returned no result");
        return result;
    }

    private String nextId(String id) {
        int separator = id.indexOf('-');
        return id.substring(0, separator + 1) + new BigInteger(id.substring(separator + 1)).add(BigInteger.ONE);
    }
}
