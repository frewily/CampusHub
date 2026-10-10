package io.github.frewily.campushub.mapper;

import io.github.frewily.campushub.entity.BlogComments;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Param;
import java.util.List;

public interface BlogCommentsMapper extends BaseMapper<BlogComments> {
    Long lockBlog(@Param("blogId") Long blogId);
    Long findBlog(@Param("blogId") Long blogId);
    BlogComments findVisibleRoot(@Param("commentId") Long commentId);
    BlogComments lockVisibleRoot(@Param("commentId") Long commentId, @Param("blogId") Long blogId);
    List<BlogComments> findDirectReplies(@Param("blogId") Long blogId, @Param("commentId") Long commentId,
                                       @Param("beforeId") Long beforeId, @Param("limit") int limit);
    int incrementCommentCount(@Param("blogId") Long blogId);
    List<BlogComments> findFirstLevelComments(@Param("blogId") Long blogId,
                                            @Param("beforeId") Long beforeId,
                                            @Param("limit") int limit);
}
