package io.github.frewily.campushub.dto.response;

import lombok.AllArgsConstructor;
import lombok.Getter;
import java.time.LocalDateTime;

/** New read API uses string IDs; the older numeric admission response is unchanged. */
@Getter
@AllArgsConstructor
public class OrderStatusResponse {
    private final String orderId;
    private final String voucherId;
    private final String status;
    private final String cancellationCompensation;
    private final LocalDateTime createTime;
    private final LocalDateTime updateTime;
}
