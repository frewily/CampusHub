package io.github.frewily.campushub.service;

import io.github.frewily.campushub.entity.BlogComments;
import com.baomidou.mybatisplus.extension.service.IService;
import io.github.frewily.campushub.dto.request.BlogCommentCreateRequest;
import io.github.frewily.campushub.dto.request.BlogCommentPageRequest;
import io.github.frewily.campushub.dto.request.BlogCommentReplyRequest;
import io.github.frewily.campushub.dto.request.BlogCommentHideRequest;
import io.github.frewily.campushub.dto.response.BlogCommentItem;
import io.github.frewily.campushub.dto.response.BlogCommentPage;
import io.github.frewily.campushub.dto.response.BlogCommentHideAuditResponse;
import io.github.frewily.campushub.dto.request.BlogCommentHideAuditPageRequest;
import io.github.frewily.campushub.dto.response.BlogCommentHideAuditPage;

public interface IBlogCommentsService extends IService<BlogComments> {
    BlogCommentItem createComment(BlogCommentCreateRequest request);

    BlogCommentPage listComments(Long blogId, BlogCommentPageRequest request);

    BlogCommentItem createReply(Long commentId, BlogCommentReplyRequest request);

    BlogCommentPage listReplies(Long commentId, BlogCommentPageRequest request);

    void withdrawReply(Long replyId);

    void withdrawComment(Long commentId);

    void hideComment(Long commentId, BlogCommentHideRequest request);

    BlogCommentHideAuditResponse queryHideAudit(Long commentId);

    BlogCommentHideAuditPage listHideAudits(Long blogId, BlogCommentHideAuditPageRequest request);
}
