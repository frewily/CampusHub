package io.github.frewily.campushub.dto.response;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/** Retained reply-hide metadata only, independent of current content and account rows. */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class BlogReplyHideAuditResponse {
    private String id;
    private String replyId;
    private String blogId;
    private String rootCommentId;
    private String operatorUserId;
    private String reason;
    private Integer fromStatus;
    private Integer toStatus;
    private LocalDateTime createTime;
}
