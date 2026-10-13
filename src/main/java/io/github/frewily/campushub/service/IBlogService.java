package io.github.frewily.campushub.service;

import io.github.frewily.campushub.dto.Result;
import io.github.frewily.campushub.dto.request.BlogEditRequest;
import io.github.frewily.campushub.entity.Blog;
import com.baomidou.mybatisplus.extension.service.IService;

public interface IBlogService extends IService<Blog> {

    Result queryHotBlog(Integer current);

    Result queryBlogById(Long id);

    Result likeBlog(Long id);

    Result queryBlogLikes(Long id);

    Result saveBlog(Blog blog);

    void editBlog(Long id, BlogEditRequest request);

    void withdrawBlog(Long id);

    Result queryBlogOfFollow(Long max, Integer offset);
}
