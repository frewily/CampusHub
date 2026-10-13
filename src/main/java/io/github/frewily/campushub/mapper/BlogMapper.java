package io.github.frewily.campushub.mapper;

import io.github.frewily.campushub.entity.Blog;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Param;

public interface BlogMapper extends BaseMapper<Blog> {
    Blog findBlogForAuthorEdit(@Param("id") Long id);

    Blog findBlogForAuthorWithdrawal(@Param("id") Long id);

    int withdrawOwnedBlog(@Param("id") Long id, @Param("userId") Long userId);

    int editBlogFields(@Param("id") Long id, @Param("userId") Long userId,
                       @Param("title") String title, @Param("images") String images,
                       @Param("content") String content);
}
