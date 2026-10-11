package io.github.frewily.campushub.service.impl;

import io.github.frewily.campushub.entity.BlogComments;
import io.github.frewily.campushub.mapper.BlogCommentsMapper;
import io.github.frewily.campushub.service.IBlogCommentsService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import org.springframework.stereotype.Service;
import io.github.frewily.campushub.dto.UserDTO;
import io.github.frewily.campushub.dto.request.BlogCommentCreateRequest;
import io.github.frewily.campushub.dto.request.BlogCommentPageRequest;
import io.github.frewily.campushub.dto.request.BlogCommentReplyRequest;
import io.github.frewily.campushub.dto.request.BlogCommentHideRequest;
import io.github.frewily.campushub.dto.response.BlogCommentItem;
import io.github.frewily.campushub.dto.response.BlogCommentPage;
import io.github.frewily.campushub.dto.response.BlogCommentHideAuditResponse;
import io.github.frewily.campushub.dto.request.BlogCommentHideAuditPageRequest;
import io.github.frewily.campushub.dto.response.BlogCommentHideAuditPage;
import io.github.frewily.campushub.exception.BusinessException;
import io.github.frewily.campushub.exception.ErrorCode;
import io.github.frewily.campushub.utils.UserHolder;
import org.springframework.dao.DataAccessException;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;
import javax.validation.Validator;
import java.util.ArrayList;
import java.util.List;

@Service
public class BlogCommentsServiceImpl extends ServiceImpl<BlogCommentsMapper, BlogComments> implements IBlogCommentsService {
    private final Validator validator;

    public BlogCommentsServiceImpl(Validator validator) { this.validator = validator; }

    @Override
    @Transactional
    public BlogCommentItem createComment(BlogCommentCreateRequest request) {
        validate(request);
        UserDTO user = UserHolder.getUser();
        if (user == null || user.getId() == null || user.getId() <= 0) {
            throw new BusinessException(ErrorCode.AUTHENTICATION_FAILED);
        }
        try {
            // All comment writers in this use case serialize on the parent post, including its counter.
            if (baseMapper.lockBlog(request.getBlogId()) == null) {
                throw new BusinessException(ErrorCode.NOT_FOUND);
            }
            BlogComments comment = new BlogComments().setBlogId(request.getBlogId()).setUserId(user.getId())
                    .setParentId(0L).setAnswerId(0L).setContent(request.getContent()).setLiked(0).setStatus(0);
            if (baseMapper.insert(comment) != 1 || comment.getId() == null
                    || baseMapper.incrementCommentCount(request.getBlogId()) != 1) {
                throw new BusinessException(ErrorCode.OPERATION_FAILED);
            }
            // Read the DB-generated creation time; a failed read also rolls back this write.
            BlogComments stored = baseMapper.selectById(comment.getId());
            if (stored == null) throw new BusinessException(ErrorCode.OPERATION_FAILED);
            return toItem(stored);
        } catch (DataAccessException error) {
            // Drivers may include SQL/content/connection information: do not log or return their details.
            throw new BusinessException(ErrorCode.INTERNAL_ERROR);
        }
    }

    @Override
    @Transactional
    public BlogCommentItem createReply(Long commentId, BlogCommentReplyRequest request) {
        validateCommentId(commentId);
        validate(request);
        UserDTO user = UserHolder.getUser();
        if (user == null || user.getId() == null || user.getId() <= 0) {
            throw new BusinessException(ErrorCode.AUTHENTICATION_FAILED);
        }
        try {
            BlogComments root = baseMapper.findVisibleRoot(commentId);
            if (root == null || baseMapper.lockBlog(root.getBlogId()) == null) {
                throw new BusinessException(ErrorCode.NOT_FOUND);
            }
            // Parent post first, root second: keep one lock order for this content write path.
            // Recheck via a current locking read, not the earlier REPEATABLE_READ snapshot.
            if (baseMapper.lockVisibleRoot(commentId, root.getBlogId()) == null) {
                throw new BusinessException(ErrorCode.NOT_FOUND);
            }
            BlogComments reply = new BlogComments().setBlogId(root.getBlogId()).setUserId(user.getId())
                    .setParentId(commentId).setAnswerId(commentId).setContent(request.getContent())
                    .setLiked(0).setStatus(0);
            if (baseMapper.insert(reply) != 1 || reply.getId() == null
                    || baseMapper.incrementCommentCount(root.getBlogId()) != 1) {
                throw new BusinessException(ErrorCode.OPERATION_FAILED);
            }
            BlogComments stored = baseMapper.selectById(reply.getId());
            if (stored == null) throw new BusinessException(ErrorCode.OPERATION_FAILED);
            return toItem(stored);
        } catch (DataAccessException error) {
            throw new BusinessException(ErrorCode.INTERNAL_ERROR);
        }
    }

    @Override
    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public BlogCommentPage listReplies(Long commentId, BlogCommentPageRequest request) {
        validateCommentId(commentId);
        validate(request);
        try {
            BlogComments root = baseMapper.findVisibleRoot(commentId);
            if (root == null || baseMapper.findBlog(root.getBlogId()) == null) {
                throw new BusinessException(ErrorCode.NOT_FOUND);
            }
            return toPage(baseMapper.findDirectReplies(root.getBlogId(), commentId,
                    request.getBeforeId(), request.getSize() + 1), request.getSize());
        } catch (DataAccessException error) {
            throw new BusinessException(ErrorCode.INTERNAL_ERROR);
        }
    }

    @Override
    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public BlogCommentPage listComments(Long blogId, BlogCommentPageRequest request) {
        if (blogId == null || blogId <= 0) throw new BusinessException(ErrorCode.VALIDATION_FAILED);
        validate(request);
        try {
            if (baseMapper.findBlog(blogId) == null) throw new BusinessException(ErrorCode.NOT_FOUND);
            List<BlogComments> rows = baseMapper.findFirstLevelComments(blogId, request.getBeforeId(), request.getSize() + 1);
            return toPage(rows, request.getSize());
        } catch (DataAccessException error) {
            throw new BusinessException(ErrorCode.INTERNAL_ERROR);
        }
    }

    private void validateCommentId(Long commentId) {
        if (commentId == null || commentId <= 0) throw new BusinessException(ErrorCode.VALIDATION_FAILED);
    }

    @Override
    @Transactional
    public void withdrawReply(Long replyId) {
        validateCommentId(replyId);
        UserDTO user = UserHolder.getUser();
        if (user == null || user.getId() == null || user.getId() <= 0) {
            throw new BusinessException(ErrorCode.AUTHENTICATION_FAILED);
        }
        try {
            // Locate only the caller's eligible row; do not reveal foreign content or allow an admin override.
            BlogComments candidate = baseMapper.findOwnedDirectReply(replyId, user.getId());
            if (candidate == null || baseMapper.lockBlog(candidate.getBlogId()) == null) {
                throw new BusinessException(ErrorCode.NOT_FOUND);
            }
            // Same writer order as creation: post before comment. A current read sees concurrent withdrawal.
            BlogComments current = baseMapper.lockOwnedDirectReply(replyId, candidate.getBlogId(), user.getId());
            if (current == null) throw new BusinessException(ErrorCode.NOT_FOUND);
            if (Integer.valueOf(3).equals(current.getStatus())) return;
            if (baseMapper.withdrawDirectReply(replyId, candidate.getBlogId(), user.getId()) != 1
                    || baseMapper.decrementCommentCount(candidate.getBlogId()) != 1) {
                throw new BusinessException(ErrorCode.OPERATION_FAILED);
            }
        } catch (DataAccessException error) {
            throw new BusinessException(ErrorCode.INTERNAL_ERROR);
        }
    }

    @Override
    @Transactional
    public void withdrawComment(Long commentId) {
        validateCommentId(commentId);
        UserDTO user = UserHolder.getUser();
        if (user == null || user.getId() == null || user.getId() <= 0) {
            throw new BusinessException(ErrorCode.AUTHENTICATION_FAILED);
        }
        try {
            BlogComments candidate = baseMapper.findOwnedFirstLevelComment(commentId, user.getId());
            if (candidate == null || baseMapper.lockBlog(candidate.getBlogId()) == null) {
                throw new BusinessException(ErrorCode.NOT_FOUND);
            }
            // Serialize with reply creation on the post, then recheck root ownership/status via current read.
            BlogComments current = baseMapper.lockOwnedFirstLevelComment(commentId, candidate.getBlogId(), user.getId());
            if (current == null) throw new BusinessException(ErrorCode.NOT_FOUND);
            if (Integer.valueOf(3).equals(current.getStatus())) return;
            // No cascade: descendants retain content/status and their authors can still withdraw them.
            if (baseMapper.withdrawFirstLevelComment(commentId, candidate.getBlogId(), user.getId()) != 1
                    || baseMapper.decrementCommentCount(candidate.getBlogId()) != 1) {
                throw new BusinessException(ErrorCode.OPERATION_FAILED);
            }
        } catch (DataAccessException error) {
            throw new BusinessException(ErrorCode.INTERNAL_ERROR);
        }
    }

    @Override
    @Transactional
    public void hideComment(Long commentId, BlogCommentHideRequest request) {
        validateCommentId(commentId);
        validate(request);
        UserDTO operator = UserHolder.getUser();
        if (operator == null || operator.getId() == null || operator.getId() <= 0) {
            throw new BusinessException(ErrorCode.AUTHENTICATION_FAILED);
        }
        try {
            // The dedicated HTTP entry is ADMIN-only; the operator is always the authenticated session.
            BlogComments candidate = baseMapper.findModeratableFirstLevelComment(commentId);
            if (candidate == null || baseMapper.lockBlog(candidate.getBlogId()) == null) {
                throw new BusinessException(ErrorCode.NOT_FOUND);
            }
            // Same post-before-comment order as author withdrawal and reply creation; recheck current state.
            BlogComments current = baseMapper.lockModeratableFirstLevelComment(commentId, candidate.getBlogId());
            if (current == null) throw new BusinessException(ErrorCode.NOT_FOUND);
            if (Integer.valueOf(2).equals(current.getStatus())) {
                // Legacy status=2 without this use case's audit is not evidence of a completed operation.
                if (baseMapper.findFirstLevelHideAudit(commentId, candidate.getBlogId()) == null) {
                    throw new BusinessException(ErrorCode.NOT_FOUND);
                }
                return;
            }
            // Preserve original content and all descendants; first successful operator/reason wins.
            if (baseMapper.hideFirstLevelComment(commentId, candidate.getBlogId()) != 1
                    || baseMapper.decrementCommentCount(candidate.getBlogId()) != 1
                    || baseMapper.insertFirstLevelHideAudit(commentId, candidate.getBlogId(),
                            operator.getId(), request.getReason()) != 1) {
                throw new BusinessException(ErrorCode.OPERATION_FAILED);
            }
        } catch (DataAccessException error) {
            throw new BusinessException(ErrorCode.INTERNAL_ERROR);
        }
    }

    @Override
    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public BlogCommentHideAuditResponse queryHideAudit(Long commentId) {
        validateCommentId(commentId);
        UserDTO user = UserHolder.getUser();
        if (user == null || user.getId() == null || user.getId() <= 0) {
            throw new BusinessException(ErrorCode.AUTHENTICATION_FAILED);
        }
        try {
            // ADMIN-only HTTP entry; read the retained audit, not current comment/post/profile data.
            // Ordinary snapshot SELECT must neither wait for the hide writer nor expose its uncommitted audit.
            BlogCommentHideAuditResponse audit = baseMapper.findFirstLevelHideAuditDetails(commentId);
            if (audit == null) throw new BusinessException(ErrorCode.NOT_FOUND);
            return audit;
        } catch (DataAccessException error) {
            throw new BusinessException(ErrorCode.INTERNAL_ERROR);
        }
    }

    @Override
    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public BlogCommentHideAuditPage listHideAudits(Long blogId, BlogCommentHideAuditPageRequest request) {
        if (blogId == null || blogId <= 0) throw new BusinessException(ErrorCode.VALIDATION_FAILED);
        validate(request);
        UserDTO user = UserHolder.getUser();
        if (user == null || user.getId() == null || user.getId() <= 0) {
            throw new BusinessException(ErrorCode.AUTHENTICATION_FAILED);
        }
        try {
            // Read stored audit metadata only; no current post/comment/profile lookup or total-count query.
            int size = request.getSize();
            List<BlogCommentHideAuditResponse> rows = baseMapper.findHideAuditsByBlog(
                    blogId, request.getBeforeId(), size + 1);
            boolean hasNext = rows.size() > size;
            List<BlogCommentHideAuditResponse> items = new ArrayList<>(rows.subList(0, Math.min(size, rows.size())));
            String nextBeforeId = hasNext ? items.get(items.size() - 1).getId() : null;
            return new BlogCommentHideAuditPage(items, hasNext, nextBeforeId);
        } catch (DataAccessException error) {
            throw new BusinessException(ErrorCode.INTERNAL_ERROR);
        }
    }

    private BlogCommentPage toPage(List<BlogComments> rows, int size) {
        boolean hasNext = rows.size() > size;
        int visible = Math.min(rows.size(), size);
        List<BlogCommentItem> items = new ArrayList<>(visible);
        for (int i = 0; i < visible; i++) items.add(toItem(rows.get(i)));
        String nextBeforeId = hasNext ? items.get(items.size() - 1).getId() : null;
        return new BlogCommentPage(items, hasNext, nextBeforeId);
    }

    private void validate(Object request) {
        if (request == null || !validator.validate(request).isEmpty()) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED);
        }
    }

    private BlogCommentItem toItem(BlogComments row) {
        BlogCommentItem item = new BlogCommentItem();
        item.setId(row.getId().toString());
        item.setBlogId(row.getBlogId().toString());
        item.setUserId(row.getUserId().toString());
        item.setContent(row.getContent());
        item.setCreateTime(row.getCreateTime());
        return item;
    }
}
