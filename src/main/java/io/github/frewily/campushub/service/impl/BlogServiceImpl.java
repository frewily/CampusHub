package io.github.frewily.campushub.service.impl;

import cn.hutool.core.bean.BeanUtil;
import cn.hutool.core.util.BooleanUtil;
import cn.hutool.core.util.StrUtil;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import io.github.frewily.campushub.dto.Result;
import io.github.frewily.campushub.dto.ScrollResult;
import io.github.frewily.campushub.dto.UserDTO;
import io.github.frewily.campushub.dto.request.BlogEditRequest;
import io.github.frewily.campushub.entity.Blog;
import io.github.frewily.campushub.entity.Follow;
import io.github.frewily.campushub.entity.User;
import io.github.frewily.campushub.exception.BusinessException;
import io.github.frewily.campushub.exception.ErrorCode;
import io.github.frewily.campushub.mapper.BlogMapper;
import io.github.frewily.campushub.service.IBlogService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import io.github.frewily.campushub.service.IFollowService;
import io.github.frewily.campushub.service.IUserService;
import io.github.frewily.campushub.utils.RedisConstants;
import io.github.frewily.campushub.utils.SystemConstants;
import io.github.frewily.campushub.utils.UserHolder;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ZSetOperations;
import org.springframework.stereotype.Service;
import org.springframework.dao.DataAccessException;
import org.springframework.transaction.annotation.Transactional;

import javax.annotation.Resource;
import javax.validation.Validator;
import java.util.*;
import java.util.stream.Collectors;

import static io.github.frewily.campushub.utils.RedisConstants.FEED_KEY;

@Service
public class BlogServiceImpl extends ServiceImpl<BlogMapper, Blog> implements IBlogService {

    @Resource
    private IUserService userService;

    @Resource
    private StringRedisTemplate stringRedisTemplate;

    @Resource
    private IFollowService followService;

    @Resource
    private Validator validator;

    @Override
    @Transactional
    public void withdrawBlog(Long id) {
        if (id == null || id <= 0) throw new BusinessException(ErrorCode.VALIDATION_FAILED);
        UserDTO user = UserHolder.getUser();
        if (user == null || user.getId() == null || user.getId() <= 0) {
            throw new BusinessException(ErrorCode.AUTHENTICATION_FAILED);
        }
        try {
            // Retained row is needed for author-only idempotence; never call generated deleteById here.
            Blog stored = baseMapper.findBlogForAuthorWithdrawal(id);
            if (stored == null) throw new BusinessException(ErrorCode.NOT_FOUND);
            if (!user.getId().equals(stored.getUserId())) {
                throw new BusinessException(ErrorCode.AUTHORIZATION_FAILED);
            }
            if (Integer.valueOf(1).equals(stored.getWithdrawn())) return;
            if (baseMapper.withdrawOwnedBlog(id, user.getId()) != 1) {
                throw new BusinessException(ErrorCode.OPERATION_FAILED);
            }
        } catch (DataAccessException error) {
            throw new BusinessException(ErrorCode.INTERNAL_ERROR);
        }
    }

    @Override
    @Transactional
    public void editBlog(Long id, BlogEditRequest request) {
        if (id == null || id <= 0 || request == null || !validator.validate(request).isEmpty()) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED);
        }
        UserDTO user = UserHolder.getUser();
        if (user == null || user.getId() == null || user.getId() <= 0) {
            throw new BusinessException(ErrorCode.AUTHENTICATION_FAILED);
        }
        try {
            // Current row lock also follows the existing comment writer's post-first lock order.
            Blog stored = baseMapper.findBlogForAuthorEdit(id);
            if (stored == null) throw new BusinessException(ErrorCode.NOT_FOUND);
            if (!user.getId().equals(stored.getUserId())) {
                throw new BusinessException(ErrorCode.AUTHORIZATION_FAILED);
            }
            String images = request.getImages();
            if (images == null || images.trim().isEmpty()) images = "";
            if (stored.getShopId() != null && images.isEmpty()) {
                throw new BusinessException(ErrorCode.VALIDATION_FAILED, "关联门店的动态图片不能为空");
            }
            // Exact repeat is a no-op, independent of the driver's changed/found row mode.
            if (Objects.equals(stored.getTitle(), request.getTitle())
                    && Objects.equals(stored.getImages(), images)
                    && Objects.equals(stored.getContent(), request.getContent())) return;
            if (baseMapper.editBlogFields(id, user.getId(), request.getTitle(), images, request.getContent()) != 1) {
                throw new BusinessException(ErrorCode.OPERATION_FAILED);
            }
        } catch (DataAccessException error) {
            throw new BusinessException(ErrorCode.INTERNAL_ERROR);
        }
    }

    @Override
    public Result queryHotBlog(Integer current) {
        Page<Blog> page = query()
                .orderByDesc("liked")
                .page(new Page<>(current, SystemConstants.MAX_PAGE_SIZE));
        List<Blog> records = page.getRecords();
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
        Blog blog = getById(id);
        if (blog == null) {
            throw new BusinessException(ErrorCode.NOT_FOUND, "动态不存在");
        }
        queryBlogUser(blog);
        isBlogLiked(blog);
        return Result.ok(blog);
    }

    private void isBlogLiked(Blog blog) {
        UserDTO user = UserHolder.getUser();
        if (user == null) {
            // 用户未登录，默认未点赞
            blog.setIsLike(false);
            return;
        }
        Long userId = user.getId();
        String key = "blog:liked:" + blog.getId();
        Double score = stringRedisTemplate.opsForZSet().score(key, userId.toString());
        blog.setIsLike(score != null);
    }

    @Override
    @Transactional
    public Result likeBlog(Long id) {
        // Serialize new likes with withdrawal before Redis. Not a DB/Redis atomicity guarantee.
        if (baseMapper.findBlogForAuthorEdit(id) == null) throw new BusinessException(ErrorCode.NOT_FOUND);
        Long userId = UserHolder.getUser().getId();
        String key = RedisConstants.BLOG_LIKED_KEY + id;
        Double score = stringRedisTemplate.opsForZSet().score(key, userId.toString());
        if (score == null) {
            boolean isSuccess = update().setSql("liked = liked + 1").eq("id", id).update();
            if (isSuccess) {
                stringRedisTemplate.opsForZSet().add(key, userId.toString(), System.currentTimeMillis());
                // 使用时间戳作为分数，保留点赞时间顺序。
            }
        }else{
            boolean isSuccess = update().setSql("liked = liked - 1").eq("id", id).gt("liked", 0).update();
            if (isSuccess) {
                stringRedisTemplate.opsForZSet().remove(key, userId.toString());
            }
        }
        return Result.ok();
    }

    @Override
    public Result queryBlogLikes(Long id) {
        if (getById(id) == null) throw new BusinessException(ErrorCode.NOT_FOUND);
        String key = RedisConstants.BLOG_LIKED_KEY + id;
        Set<String> top5 = stringRedisTemplate.opsForZSet().range(key, 0, 4);
        if (top5 == null || top5.isEmpty()) {
            return Result.ok(Collections.emptyList());
        }
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
        return Result.ok(userDTOS);
    }

    @Override
    public Result saveBlog(Blog blog) {
        if (blog.getShopId() != null && blog.getShopId() <= 0) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED, "门店ID必须为正数");
        }
        if (blog.getTitle() == null || blog.getTitle().trim().isEmpty()) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED, "标题不能为空");
        }
        UserDTO user = UserHolder.getUser();
        blog.setUserId(user.getId());
        blog.setWithdrawn(0);
        boolean isSuccess = save(blog);
        if (!isSuccess) {
            throw new BusinessException(ErrorCode.OPERATION_FAILED, "动态发布失败");
        }
        List<Follow> follows = followService.query().eq("follow_user_id", user.getId()).list();
        for (Follow follow : follows) {
            Long userId = follow.getUserId();
            stringRedisTemplate.opsForZSet().add(FEED_KEY + userId, blog.getId().toString(), System.currentTimeMillis());
        }
        return Result.ok(blog.getId());
    }

    @Override
    public Result queryBlogOfFollow(Long max, Integer offset) {
        Long userId = UserHolder.getUser().getId();
        String key = FEED_KEY + userId;
        Set<ZSetOperations.TypedTuple<String>> typedTuples = stringRedisTemplate.opsForZSet()
                .reverseRangeByScoreWithScores(key, 0, max, offset, 2);
        if (typedTuples == null || typedTuples.isEmpty()) {
            return Result.ok();
        }
        List<Long> ids = new ArrayList<>(typedTuples.size());
        long minTime = 0;
        int os = 1;
        for (ZSetOperations.TypedTuple<String> tuple : typedTuples) {
            ids.add(Long.valueOf(Objects.requireNonNull(tuple.getValue())));
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
        // Continue the same score group cumulatively; a lower ending score starts its own offset.
        // Count raw Redis members, including rows subsequently hidden/missing in the database.
        if (minTime == max.longValue()) {
            if (offset > Integer.MAX_VALUE - os) {
                throw new BusinessException(ErrorCode.OPERATION_FAILED, "Feed分页偏移超出支持范围");
            }
            os += offset;
        }
        String idStr = StrUtil.join(",", ids);
        List<Blog> blogs = query().in("id", ids)
                .last("ORDER BY FIELD(id," + idStr + ")").list();
        for (Blog blog : blogs) {
            queryBlogUser(blog);
            isBlogLiked(blog);
        }
        ScrollResult r = new ScrollResult();
        r.setList(blogs);
        r.setOffset(os);
        r.setMinTime(minTime);
        return Result.ok(r);
    }
}
