package io.github.frewily.campushub.mapper;

import io.github.frewily.campushub.entity.BlogComments;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import io.github.frewily.campushub.dto.response.BlogCommentHideAuditResponse;
import org.apache.ibatis.annotations.Param;
import java.util.List;

public interface BlogCommentsMapper extends BaseMapper<BlogComments> {
    Long lockVisibleBlog(@Param("blogId") Long blogId);
    Long lockBlog(@Param("blogId") Long blogId);
    Long findBlog(@Param("blogId") Long blogId);
    BlogComments findVisibleRoot(@Param("commentId") Long commentId);
    BlogComments lockVisibleRoot(@Param("commentId") Long commentId, @Param("blogId") Long blogId);
    List<BlogComments> findDirectReplies(@Param("blogId") Long blogId, @Param("commentId") Long commentId,
                                       @Param("beforeId") Long beforeId, @Param("limit") int limit);
    int incrementCommentCount(@Param("blogId") Long blogId);
    BlogComments findOwnedDirectReply(@Param("replyId") Long replyId, @Param("userId") Long userId);
    BlogComments lockOwnedDirectReply(@Param("replyId") Long replyId, @Param("blogId") Long blogId,
                                     @Param("userId") Long userId);
    int withdrawDirectReply(@Param("replyId") Long replyId, @Param("blogId") Long blogId,
                            @Param("userId") Long userId);
    int decrementCommentCount(@Param("blogId") Long blogId);
    BlogComments findOwnedFirstLevelComment(@Param("commentId") Long commentId, @Param("userId") Long userId);
    BlogComments lockOwnedFirstLevelComment(@Param("commentId") Long commentId, @Param("blogId") Long blogId,
                                            @Param("userId") Long userId);
    int withdrawFirstLevelComment(@Param("commentId") Long commentId, @Param("blogId") Long blogId,
                                  @Param("userId") Long userId);
    BlogComments findModeratableFirstLevelComment(@Param("commentId") Long commentId);
    BlogComments lockModeratableFirstLevelComment(@Param("commentId") Long commentId, @Param("blogId") Long blogId);
    int hideFirstLevelComment(@Param("commentId") Long commentId, @Param("blogId") Long blogId);
    Long findFirstLevelHideAudit(@Param("commentId") Long commentId, @Param("blogId") Long blogId);
    BlogCommentHideAuditResponse findFirstLevelHideAuditDetails(@Param("commentId") Long commentId);
    List<BlogCommentHideAuditResponse> findHideAuditsByBlog(@Param("blogId") Long blogId,
                                                         @Param("beforeId") Long beforeId,
                                                         @Param("limit") int limit);
    int insertFirstLevelHideAudit(@Param("commentId") Long commentId, @Param("blogId") Long blogId,
                                 @Param("operatorUserId") Long operatorUserId, @Param("reason") String reason);
    List<BlogComments> findFirstLevelComments(@Param("blogId") Long blogId,
                                            @Param("beforeId") Long beforeId,
                                            @Param("limit") int limit);
}
