package io.github.frewily.campushub.service;

import io.github.frewily.campushub.entity.OrderCancellation;
import io.github.frewily.campushub.mapper.OrderCancellationMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import java.util.*;

@Slf4j
@Component
public class OrderCancellationReconciler {
    private static final DefaultRedisScript<Long> RELEASE = new DefaultRedisScript<>();
    static {
        RELEASE.setLocation(new ClassPathResource("release-cancelled-order.lua"));
        RELEASE.setResultType(Long.class);
    }
    private final OrderCancellationMapper cancellations;
    private final StringRedisTemplate redis;
    private final boolean enabled;

    public OrderCancellationReconciler(OrderCancellationMapper cancellations, StringRedisTemplate redis,
            @Value("${campushub.order.cancellation-reconciler-enabled:true}") boolean enabled) {
        this.cancellations = cancellations;
        this.redis = redis;
        this.enabled = enabled;
    }

    @Scheduled(fixedDelay = 5000)
    public void reconcileDue() {
        if (!enabled) return;
        try {
            for (OrderCancellation entry : cancellations.due()) {
                try { reconcile(entry); }
                catch (RuntimeException error) {
                    log.warn("Cancellation reconciliation unavailable, orderId={}, errorClass={}",
                            entry.getOrderId(), error.getClass().getSimpleName());
                }
            }
        } catch (RuntimeException error) {
            log.warn("Cancellation outbox unavailable, errorClass={}", error.getClass().getSimpleName());
        }
    }

    public void reconcile(OrderCancellation entry) {
        if (entry.getAttempts() != null && entry.getAttempts() >= 10) {
            cancellations.exhaust(entry.getOrderId());
            return;
        }
        String token = UUID.randomUUID().toString();
        if (cancellations.claim(entry.getOrderId(), token) != 1) return;
        Long result;
        try {
            result = redis.execute(RELEASE, Arrays.asList("seckill:stock:" + entry.getVoucherId(),
                    "seckill:activity:" + entry.getVoucherId(), "seckill:request:" + entry.getVoucherId(),
                    "seckill:released:" + entry.getVoucherId()), entry.getUserId().toString(),
                    entry.getOrderId().toString(), entry.getExpiresAtMs().toString());
        } catch (RuntimeException error) {
            // Connection loss may happen after a successful Lua call; an idempotent retry is required.
            cancellations.retry(entry.getOrderId(), token, error.getClass().getSimpleName());
            return;
        }
        if (result == null) {
            cancellations.retry(entry.getOrderId(), token, "UnconfirmedRedisResult");
        } else if (result >= 0 && result <= 2) {
            String[] outcomes = {"RELEASED", "ALREADY_RELEASED", "EXPIRED"};
            // If this write fails, lease expiry permits another worker to repeat the safe Redis call.
            cancellations.complete(entry.getOrderId(), token, outcomes[result.intValue()]);
        } else {
            cancellations.review(entry.getOrderId(), token,
                    result == 4 ? "UnmatchedReservation" : "InvalidRedisState");
        }
    }
}
