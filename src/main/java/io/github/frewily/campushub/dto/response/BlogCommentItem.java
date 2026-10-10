package io.github.frewily.campushub.dto.response;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class BlogCommentItem {
    private String id;
    private String blogId;
    private String userId;
    private String content;
    private LocalDateTime createTime;
}
