package io.github.frewily.campushub.entity;

import lombok.Data;
import lombok.experimental.Accessors;

@Data
@Accessors(chain = true)
public class BlogFeedDelivery {
    private Long blogId;
    private Long recipientUserId;
    private Long scoreMillis;
    private Integer attempts;
    private String lastError;
}
