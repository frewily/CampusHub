package io.github.frewily.campushub.service;

import io.github.frewily.campushub.entity.BlogComments;
import com.baomidou.mybatisplus.extension.service.IService;
import io.github.frewily.campushub.dto.request.BlogCommentCreateRequest;
import io.github.frewily.campushub.dto.request.BlogCommentPageRequest;
import io.github.frewily.campushub.dto.response.BlogCommentItem;
import io.github.frewily.campushub.dto.response.BlogCommentPage;

public interface IBlogCommentsService extends IService<BlogComments> {
    BlogCommentItem createComment(BlogCommentCreateRequest request);

    BlogCommentPage listComments(Long blogId, BlogCommentPageRequest request);
}
