package io.github.frewily.campushub.entity;

import lombok.Data;
import lombok.experimental.Accessors;

/** An immutable successful publication receipt, not the current post content. */
@Data
@Accessors(chain = true)
public class BlogPublicationRequest {
    private Long userId;
    private String requestKey;
    private String requestHash;
    private Long blogId;
}
