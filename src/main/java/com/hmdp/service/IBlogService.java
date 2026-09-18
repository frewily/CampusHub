package com.hmdp.service;

import com.hmdp.dto.Result;
import com.hmdp.entity.Blog;
import com.baomidou.mybatisplus.extension.service.IService;

/**
 * <p>
 *  服务类
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
public interface IBlogService extends IService<Blog> {

    // 查询热门博客
    Result queryHotBlog(Integer current);

    // 查询博客详情
    Result queryBlogById(Long id);

    // 点赞博客
    Result likeBlog(Long id);

    // 查询博客点赞数
    Result queryBlogLikes(Long id);

    // 保存博客
    Result saveBlog(Blog blog);

    // 查询当前用户所关注用户所发布的博客
    Result queryBlogOfFollow(Long max, Integer offset);
}
