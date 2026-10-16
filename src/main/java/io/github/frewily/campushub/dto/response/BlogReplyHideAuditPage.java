package io.github.frewily.campushub.dto.response;

import lombok.AllArgsConstructor;
import lombok.Getter;

import java.util.List;

@Getter
@AllArgsConstructor
public class BlogReplyHideAuditPage {
    private final List<BlogReplyHideAuditResponse> items;
    private final boolean hasNext;
    private final String nextBeforeId;
}
