package io.github.frewily.campushub.entity;

import lombok.Data;
import lombok.experimental.Accessors;

@Data
@Accessors(chain = true)
public class OrderCancellation {
    private Long orderId;
    private Long userId;
    private Long voucherId;
    private Long expiresAtMs;
    private String status;
    private Integer attempts;
    private String leaseToken;
    private String lastError;
    private String redisResult;
}
