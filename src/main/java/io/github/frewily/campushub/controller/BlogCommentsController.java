package io.github.frewily.campushub.controller;

import io.github.frewily.campushub.dto.Result;
import io.github.frewily.campushub.dto.request.BlogCommentCreateRequest;
import io.github.frewily.campushub.dto.request.BlogCommentPageRequest;
import io.github.frewily.campushub.dto.request.BlogCommentReplyRequest;
import io.github.frewily.campushub.service.IBlogCommentsService;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import javax.validation.Valid;
import javax.validation.constraints.NotNull;
import javax.validation.constraints.Positive;

@RestController
@RequestMapping("/blog-comments")
@Validated
public class BlogCommentsController {
    private final IBlogCommentsService blogCommentsService;

    public BlogCommentsController(IBlogCommentsService blogCommentsService) {
        this.blogCommentsService = blogCommentsService;
    }

    @PostMapping
    @PreAuthorize("hasAnyRole('USER', 'MERCHANT', 'ADMIN')")
    public Result createComment(@Valid @NotNull @RequestBody BlogCommentCreateRequest request) {
        return Result.ok(blogCommentsService.createComment(request));
    }

    @GetMapping("/of/blog/{blogId}")
    public Result listComments(@Positive @PathVariable("blogId") Long blogId,
                               @Valid @ModelAttribute BlogCommentPageRequest request) {
        return Result.ok(blogCommentsService.listComments(blogId, request));
    }

    @PostMapping("/{commentId}/replies")
    @PreAuthorize("hasAnyRole('USER', 'MERCHANT', 'ADMIN')")
    public Result createReply(@Positive @PathVariable("commentId") Long commentId,
                              @Valid @NotNull @RequestBody BlogCommentReplyRequest request) {
        return Result.ok(blogCommentsService.createReply(commentId, request));
    }

    @GetMapping("/{commentId}/replies")
    public Result listReplies(@Positive @PathVariable("commentId") Long commentId,
                             @Valid @ModelAttribute BlogCommentPageRequest request) {
        return Result.ok(blogCommentsService.listReplies(commentId, request));
    }
}
