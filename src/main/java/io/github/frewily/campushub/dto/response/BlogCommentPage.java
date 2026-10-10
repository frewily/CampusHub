package io.github.frewily.campushub.dto.response;

import lombok.AllArgsConstructor;
import lombok.Getter;

import java.util.List;

@Getter
@AllArgsConstructor
public class BlogCommentPage {
    private final List<BlogCommentItem> items;
    private final boolean hasNext;
    private final String nextBeforeId;
}
