package io.github.frewily.campushub.dto.response;

import lombok.AllArgsConstructor;
import lombok.Getter;

import java.util.List;

@Getter
@AllArgsConstructor
public class BlogCommentHideAuditPage {
    private final List<BlogCommentHideAuditResponse> items;
    private final boolean hasNext;
    private final String nextBeforeId;
}
