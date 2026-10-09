package io.github.frewily.campushub.service;

import io.github.frewily.campushub.entity.OrderCancellation;
import io.github.frewily.campushub.mapper.OrderCancellationMapper;
import io.github.frewily.campushub.observability.DiagnosticCounters;
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
    public enum DiagnosticEvent {
        CLAIM_MISSED, EXHAUSTED, RETRY_RECORDED, RELEASED,
        ALREADY_RELEASED, EXPIRED, REVIEW_RECORDED,
        STALE, ENTRY_FAILURE, POLL_FAILURE, UNCONFIRMED
    }

    private static final DefaultRedisScript<Long> RELEASE = new DefaultRedisScript<>();
    static {
        RELEASE.setLocation(new ClassPathResource("release-cancelled-order.lua"));
        RELEASE.setResultType(Long.class);
    }
    private final OrderCancellationMapper cancellations;
    private final StringRedisTemplate redis;
    private final boolean enabled;
    private final DiagnosticCounters<DiagnosticEvent> diagnostics =
            new DiagnosticCounters<>(DiagnosticEvent.class);

    public DiagnosticCounters<DiagnosticEvent> diagnostics() { return diagnostics; }

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
                    diagnostics.increment(DiagnosticEvent.ENTRY_FAILURE);
                    log.warn("Cancellation reconciliation unavailable, orderId={}, errorClass={}",
                            entry.getOrderId(), error.getClass().getSimpleName());
                }
            }
        } catch (RuntimeException error) {
            diagnostics.increment(DiagnosticEvent.POLL_FAILURE);
            log.warn("Cancellation outbox unavailable, errorClass={}", error.getClass().getSimpleName());
        }
    }

    public void reconcile(OrderCancellation entry) {
        if (entry.getAttempts() != null && entry.getAttempts() >= 10) {
            recordCasResult(cancellations.exhaust(entry.getOrderId()), DiagnosticEvent.EXHAUSTED);
            return;
        }
        String token = UUID.randomUUID().toString();
        int claimed = cancellations.claim(entry.getOrderId(), token);
        if (claimed != 1) {
            if (claimed == 0) {
                diagnostics.increment(DiagnosticEvent.CLAIM_MISSED);
                diagnostics.increment(DiagnosticEvent.STALE);
            }
            else diagnostics.increment(DiagnosticEvent.UNCONFIRMED);
            return;
        }
        Long result;
        try {
            result = redis.execute(RELEASE, Arrays.asList("seckill:stock:" + entry.getVoucherId(),
                    "seckill:activity:" + entry.getVoucherId(), "seckill:request:" + entry.getVoucherId(),
                    "seckill:released:" + entry.getVoucherId()), entry.getUserId().toString(),
                    entry.getOrderId().toString(), entry.getExpiresAtMs().toString());
        } catch (RuntimeException error) {
            // Connection loss may happen after a successful Lua call; an idempotent retry is required.
            recordCasResult(cancellations.retry(entry.getOrderId(), token, error.getClass().getSimpleName()),
                    DiagnosticEvent.RETRY_RECORDED);
            return;
        }
        if (result == null) {
            recordCasResult(cancellations.retry(entry.getOrderId(), token, "UnconfirmedRedisResult"),
                    DiagnosticEvent.RETRY_RECORDED);
        } else if (result >= 0 && result <= 2) {
            DiagnosticEvent[] outcomes = {DiagnosticEvent.RELEASED,
                    DiagnosticEvent.ALREADY_RELEASED, DiagnosticEvent.EXPIRED};
            // If this write fails, lease expiry permits another worker to repeat the safe Redis call.
            recordCasResult(cancellations.complete(entry.getOrderId(), token,
                    outcomes[result.intValue()].name()), outcomes[result.intValue()]);
        } else {
            recordCasResult(cancellations.review(entry.getOrderId(), token,
                    result == 4 ? "UnmatchedReservation" : "InvalidRedisState"),
                    DiagnosticEvent.REVIEW_RECORDED);
        }
    }

    private void recordCasResult(int rows, DiagnosticEvent confirmedEvent) {
        if (rows == 1) diagnostics.increment(confirmedEvent);
        else if (rows == 0) diagnostics.increment(DiagnosticEvent.STALE);
        else diagnostics.increment(DiagnosticEvent.UNCONFIRMED);
    }
}
