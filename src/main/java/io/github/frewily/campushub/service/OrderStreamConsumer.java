package io.github.frewily.campushub.service;

import io.github.frewily.campushub.entity.VoucherOrder;
import io.github.frewily.campushub.exception.OrderProcessingException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.connection.stream.MapRecord;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import javax.annotation.PostConstruct;
import javax.annotation.PreDestroy;
import java.time.Duration;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.*;

@Slf4j
@Component
public class OrderStreamConsumer {
    private final IVoucherOrderService orders;
    private final OrderStreamQueue queue;
    private final boolean enabled;
    private final String consumerName = "order-" + UUID.randomUUID();
    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private volatile boolean running;

    @Autowired
    public OrderStreamConsumer(IVoucherOrderService orders, StringRedisTemplate redis,
            @Value("${campushub.order.stream-consumer-enabled:true}") boolean enabled,
            @Value("${campushub.order.claim-idle-ms:60000}") long claimIdleMillis,
            @Value("${campushub.order.max-attempts:5}") int maxAttempts) {
        if (claimIdleMillis < 1000) throw new IllegalArgumentException("Claim idle must be at least 1000ms");
        this.orders = orders;
        this.enabled = enabled;
        this.queue = new OrderStreamQueue(redis, "stream.orders", "g1", consumerName,
                Duration.ofMillis(claimIdleMillis), maxAttempts);
    }

    // Test seam: same orchestration with an isolated queue, without a background process.
    OrderStreamConsumer(IVoucherOrderService orders, OrderStreamQueue queue) {
        this.orders = orders;
        this.queue = queue;
        this.enabled = false;
    }

    @PostConstruct
    public void start() {
        if (!enabled) return;
        queue.initialize();
        running = true;
        log.info("Order consumer started, consumer={}", consumerName);
        executor.submit(this::run);
    }

    private void run() {
        while (running && !Thread.currentThread().isInterrupted()) {
            try {
                consumeOnce(); // recovery runs at startup AND every round, even when there are no new events
            } catch (RuntimeException error) {
                if (!running) return;
                // Never log exception messages/payloads: dependency errors may contain sensitive data.
                log.warn("Order consumer infrastructure failure, consumer={}, errorClass={}",
                        consumerName, error.getClass().getSimpleName());
                try { Thread.sleep(1000); }
                catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); return; }
            }
        }
    }

    void consumeOnce() {
        for (MapRecord<String, Object, Object> record : queue.recoverPending()) {
            if (Thread.currentThread().isInterrupted()) return;
            processSafely(record);
        }
        if (Thread.currentThread().isInterrupted()) return;
        for (MapRecord<String, Object, Object> record : queue.readNew(Duration.ofSeconds(2))) {
            if (Thread.currentThread().isInterrupted()) return;
            processSafely(record);
        }
    }

    private void processSafely(MapRecord<String, Object, Object> record) {
        try { process(record); }
        catch (RuntimeException infrastructureError) {
            // Corrupt state/archival failures are isolated per record. Do not ACK or hide the pending entry.
            log.warn("Order transition unavailable, sourceId={}, consumer={}, errorClass={}",
                    record.getId(), consumerName, infrastructureError.getClass().getSimpleName());
        }
    }

    void process(MapRecord<String, Object, Object> record) {
        long attempt = queue.begin(record.getId());
        if (attempt < 0) return;
        if (attempt == 0) { archiveOrDefer(record, "RetryBudgetExhausted"); return; }
        try {
            orders.handleVoucherOrder(decode(record.getValue()));
        } catch (RuntimeException error) {
            archiveOrDefer(record, error instanceof OrderProcessingException
                    ? ((OrderProcessingException) error).getReason().name() : error.getClass().getSimpleName());
            return;
        }
        // ACK errors are NOT persistence failures. A redelivery rechecks DB idempotency.
        queue.success(record.getId());
    }

    private void archiveOrDefer(MapRecord<String, Object, Object> record, String classification) {
        long result = queue.failure(record.getId(), classification);
        log.warn("Order attempt failed, sourceId={}, consumer={}, disposition={}, errorClass={}",
                record.getId(), consumerName, result == 1 ? "REQUIRES_REVIEW" : result == 0 ? "PENDING" : "STALE",
                classification);
    }

    static VoucherOrder decode(Map<Object, Object> fields) {
        if (fields.size() != 3) throw new IllegalArgumentException("Invalid order event fields");
        return new VoucherOrder().setId(positiveId(fields.get("id")))
                .setUserId(positiveId(fields.get("userId"))).setVoucherId(positiveId(fields.get("voucherId")));
    }

    private static long positiveId(Object value) {
        if (!(value instanceof String) || !((String) value).matches("[1-9][0-9]{0,18}")) {
            throw new IllegalArgumentException("Invalid order event identifier");
        }
        return Long.parseLong((String) value);
    }

    @PreDestroy
    public void stop() {
        running = false;
        executor.shutdownNow();
        try {
            if (!executor.awaitTermination(10, TimeUnit.SECONDS)) {
                log.warn("Order worker still completing; pending recovery and DB idempotency remain required");
            }
        } catch (InterruptedException error) { Thread.currentThread().interrupt(); }
        // Do not DELCONSUMER: it would erase that consumer's pending entries.
    }
}
