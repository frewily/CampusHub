package com.hmdp.service.impl;

import cn.hutool.core.bean.BeanUtil;
import cn.hutool.core.util.BooleanUtil;
import cn.hutool.core.util.StrUtil;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.hmdp.dto.Result;
import com.hmdp.dto.ScrollResult;
import com.hmdp.dto.UserDTO;
import com.hmdp.entity.Blog;
import com.hmdp.entity.Follow;
import com.hmdp.entity.User;
import com.hmdp.mapper.BlogMapper;
import com.hmdp.service.IBlogService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.service.IFollowService;
import com.hmdp.service.IUserService;
import com.hmdp.utils.RedisConstants;
import com.hmdp.utils.SystemConstants;
import com.hmdp.utils.UserHolder;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ZSetOperations;
import org.springframework.stereotype.Service;

import javax.annotation.Resource;
import java.util.*;
import java.util.stream.Collectors;

import static com.hmdp.utils.RedisConstants.FEED_KEY;

/**
 * <p>
 *  服务实现类
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
@Service
public class BlogServiceImpl extends ServiceImpl<BlogMapper, Blog> implements IBlogService {

    @Resource
    private IUserService userService;

    @Resource
    private StringRedisTemplate stringRedisTemplate;

    @Resource
    private IFollowService followService;

    @Override
    public Result queryHotBlog(Integer current) {
        // 根据用户查询
        Page<Blog> page = query()
                .orderByDesc("liked")
                .page(new Page<>(current, SystemConstants.MAX_PAGE_SIZE));
        // 获取当前页数据
        List<Blog> records = page.getRecords();
        // 查询用户
        records.forEach(blog -> {
            this.queryBlogUser(blog);
            this.isBlogLiked(blog);
        });
        return Result.ok(records);
    }

    private void queryBlogUser(Blog blog) {
        Long userId = blog.getUserId();
        User user = userService.getById(userId);
        blog.setName(user.getNickName());
        blog.setIcon(user.getIcon());
    }

    @Override
    public Result queryBlogById(Long id) {
        //1 查询blog
        Blog blog = getById(id);
        if (blog == null) {
            return Result.fail("笔记不存在！");
        }
        //2 查询blog有关的用户
        queryBlogUser(blog);
        //3 查询blog是否被点赞
        isBlogLiked(blog);
        return Result.ok(blog);
    }

    private void isBlogLiked(Blog blog) {
        //1 获取用户
        UserDTO user = UserHolder.getUser();
        if (user == null) {
            // 用户未登录，默认未点赞
            blog.setIsLike(false);
            return;
        }
        Long userId = user.getId();
        //2 判断当前用户是否已经点赞
        String key = "blog:liked:" + blog.getId();
        Double score = stringRedisTemplate.opsForZSet().score(key, userId.toString());
        //3 设置值
        blog.setIsLike(score != null);
    }

    @Override
    public Result likeBlog(Long id) {
        //1 获取用户
        Long userId = UserHolder.getUser().getId();
        //2 判断当前用户是否已经点赞
        String key = RedisConstants.BLOG_LIKED_KEY + id;
        Double score = stringRedisTemplate.opsForZSet().score(key, userId.toString());
        //3 如果未点赞，可以点赞
        if (score == null) {
            //3.1 数据库点赞数加1
            boolean isSuccess = update().setSql("liked = liked + 1").eq("id", id).update();
            //3.2 保存用户点赞信息到redis的ZSet集合中
            if (isSuccess) {
                stringRedisTemplate.opsForZSet().add(key, userId.toString(), System.currentTimeMillis());
                //用时间戳表示分数，进行点赞排序
            }
        }else{
            //4 如果已经点赞，取消点赞
            boolean isSuccess = update().setSql("liked = liked - 1").eq("id", id).gt("liked", 0).update();
            if (isSuccess) {
                //4.1 移除redis中用户点赞信息
                stringRedisTemplate.opsForZSet().remove(key, userId.toString());
            }
        }
        return Result.ok();
    }

    @Override
    public Result queryBlogLikes(Long id) {
        String key = RedisConstants.BLOG_LIKED_KEY + id;
        //1 查询top5的点赞用户
        Set<String> top5 = stringRedisTemplate.opsForZSet().range(key, 0, 4);
        if (top5 == null || top5.isEmpty()) {
            return Result.ok(Collections.emptyList());
        }
        //2 解析出其中的用户id
        List<Long> ids = top5.stream().map(Long::valueOf).collect(Collectors.toList());
        /**
         * .map(Long::valueOf)
         * map 是转换操作，对流中的每个元素进行处理
         * Long::valueOf 是方法引用，等同于 s -> Long.valueOf(s)
         * 作用：将每个字符串类型的ID（如 "123"）转换为 Long 类型（如 123L）
         * .collect(Collectors.toList())
         * 将处理后的流收集成一个 List
         * 最终得到 List<Long> 类型的用户ID列表
         */
        String idStr = StrUtil.join(",", ids);
        //3 查询用户信息
        List<UserDTO> userDTOS = userService.query()
                .in("id", ids)
                .last("ORDER BY FIELD(id," + idStr + ")").list()
                .stream()
                .map(user -> BeanUtil.copyProperties(user, UserDTO.class))
                .collect(Collectors.toList());
        /**
         * .stream()
         * 将查询结果转换成流，方便进行链式操作
         * .map(user -> BeanUtil.copyProperties(user, UserDTO.class))
         * map 对流中的每个 User 对象进行转换
         * BeanUtil.copyProperties(user, UserDTO.class) 是 Hutool 工具类的方法
         * 作用： 将 User 对象的属性复制到 UserDTO 对象中
         * 这是一个对象转换操作，从数据库实体转换为数据传输对象
         * .collect(Collectors.toList())
         * 将转换后的流收集成 List
         * 最终得到 List<UserDTO> 类型
         */
        //4 返回用户信息
        return Result.ok(userDTOS);
    }

    @Override
    public Result saveBlog(Blog blog) {
        // 参数校验
        if (blog.getShopId() == null) {
            return Result.fail("商铺ID不能为空");
        }
        if (blog.getTitle() == null || blog.getTitle().trim().isEmpty()) {
            return Result.fail("标题不能为空");
        }
        //1 获取登录用户
        UserDTO user = UserHolder.getUser();
        blog.setUserId(user.getId());
        //2 保存探店博文
        boolean isSuccess = save(blog);
        if (!isSuccess) {
            return Result.fail("发布失败");
        }
        //3 查询笔记作者所有的粉丝  select * from tb_follow where follow_user_id = ?
        List<Follow> follows = followService.query().eq("follow_user_id", user.getId()).list();
        //4 推送笔记给粉丝
        for (Follow follow : follows) {
            //4.1 获取粉丝id
            Long userId = follow.getUserId();
            //4.2 推送
            stringRedisTemplate.opsForZSet().add(FEED_KEY, blog.getId().toString(), System.currentTimeMillis());
        }
        // 返回id
        return Result.ok(blog.getId());
    }

    @Override
    public Result queryBlogOfFollow(Long max, Integer offset) {
        //1 获取当前用户
        Long userId = UserHolder.getUser().getId();
        //2 查询收件箱
        String key = FEED_KEY + userId;
        Set<ZSetOperations.TypedTuple<String>> typedTuples = stringRedisTemplate.opsForZSet()
                .reverseRangeByScoreWithScores(key, 0, max, offset, 2);
        //非空判断
        if (typedTuples == null || typedTuples.isEmpty()) {
            return Result.ok();
        }
        //3 解析数据，blogId，score(时间戳)，offset
        List<Long> ids = new ArrayList<>(typedTuples.size());
        long minTime = 0;
        int os = 1;
        for (ZSetOperations.TypedTuple<String> tuple : typedTuples) {
            //4.1 获取id
            ids.add(Long.valueOf(Objects.requireNonNull(tuple.getValue())));
            //4.2 获取分数(时间戳)
            long time = Objects.requireNonNull(tuple.getScore()).longValue();
            /**
             * Objects.requireNonNull() 的作用是空值检查：
             * 功能：
             * 如果传入的参数为 null，会抛出 NullPointerException
             * 如果参数不为 null，则返回该参数本身
             */
            if (time == minTime) {
                os++;
            }else{
                minTime = time;
                os = 1;
            }
        }
        //4 根据id查询blog
        String idStr = StrUtil.join(",", ids);
        List<Blog> blogs = query().in("id", ids)
                .last("ORDER BY FIELD(id," + idStr + ")").list();
        for (Blog blog : blogs) {
            //5 查询blog有关的用户
            queryBlogUser(blog);
            //6 查询blog是否被点赞
            isBlogLiked(blog);
        }
        //7 封装并返回
        ScrollResult r = new ScrollResult();
        r.setList(blogs);
        r.setOffset(os);
        r.setMinTime(minTime);
        return Result.ok(r);
    }
}
