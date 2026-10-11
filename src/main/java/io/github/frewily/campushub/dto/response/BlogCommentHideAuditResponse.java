package io.github.frewily.campushub.dto.response;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/** Stored moderation metadata only; does not describe the comment's current state. */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class BlogCommentHideAuditResponse {
    private String id;
    private String commentId;
    private String blogId;
    private String operatorUserId;
    private String reason;
    private Integer fromStatus;
    private Integer toStatus;
    private LocalDateTime createTime;
}
