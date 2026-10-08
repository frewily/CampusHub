package io.github.frewily.campushub.dto.response;

import io.github.frewily.campushub.dto.Result;
import lombok.Getter;

/** ACCEPTED means queued in Redis, not committed to MySQL or paid. */
@Getter
public class OrderAcceptanceResult extends Result {
    private final String acceptanceStatus = "ACCEPTED";
    private final boolean replayed;

    public OrderAcceptanceResult(Long orderId, boolean replayed) {
        super(true, null, null, orderId, null);
        this.replayed = replayed;
    }
}
