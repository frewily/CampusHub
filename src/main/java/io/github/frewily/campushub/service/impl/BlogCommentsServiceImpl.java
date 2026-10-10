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
import io.github.frewily.campushub.dto.response.BlogCommentItem;
import io.github.frewily.campushub.dto.response.BlogCommentPage;
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
