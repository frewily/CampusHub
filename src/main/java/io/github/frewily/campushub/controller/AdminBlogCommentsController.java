package io.github.frewily.campushub.controller;

import io.github.frewily.campushub.dto.Result;
import io.github.frewily.campushub.dto.request.BlogCommentHideRequest;
import io.github.frewily.campushub.dto.request.BlogCommentHideAuditPageRequest;
import io.github.frewily.campushub.service.IBlogCommentsService;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import javax.validation.Valid;
import javax.validation.constraints.NotNull;
import javax.validation.constraints.Positive;

@RestController
@RequestMapping("/admin/blog-comments")
@Validated
public class AdminBlogCommentsController {
    private final IBlogCommentsService blogCommentsService;

    public AdminBlogCommentsController(IBlogCommentsService blogCommentsService) {
        this.blogCommentsService = blogCommentsService;
    }

    @GetMapping("/{commentId}/hide-audit")
    @PreAuthorize("hasRole('ADMIN')")
    public Result queryHideAudit(@Positive @PathVariable("commentId") Long commentId) {
        return Result.ok(blogCommentsService.queryHideAudit(commentId));
    }

    @GetMapping("/hide-audits/of/blog/{blogId}")
    @PreAuthorize("hasRole('ADMIN')")
    public Result listHideAudits(@Positive @PathVariable("blogId") Long blogId,
                                 @Valid @ModelAttribute BlogCommentHideAuditPageRequest request) {
        return Result.ok(blogCommentsService.listHideAudits(blogId, request));
    }

    @PostMapping("/{commentId}/hide")
    @PreAuthorize("hasRole('ADMIN')")
    public Result hideComment(@Positive @PathVariable("commentId") Long commentId,
                              @Valid @NotNull @RequestBody BlogCommentHideRequest request) {
        blogCommentsService.hideComment(commentId, request);
        return Result.ok();
    }

    @PostMapping("/replies/{replyId}/hide")
    @PreAuthorize("hasRole('ADMIN')")
    public Result hideReply(@Positive @PathVariable("replyId") Long replyId,
                            @Valid @NotNull @RequestBody BlogCommentHideRequest request) {
        blogCommentsService.hideReply(replyId, request);
        return Result.ok();
    }
}
