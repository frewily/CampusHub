package com.hmdp.service.impl;

import cn.hutool.core.bean.BeanUtil;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.hmdp.dto.Result;
import com.hmdp.dto.UserDTO;
import com.hmdp.entity.Follow;
import com.hmdp.entity.User;
import com.hmdp.mapper.FollowMapper;
import com.hmdp.service.IFollowService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.service.IUserService;
import com.hmdp.utils.UserHolder;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import javax.annotation.Resource;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * <p>
 *  服务实现类
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
@Service
public class FollowServiceImpl extends ServiceImpl<FollowMapper, Follow> implements IFollowService {

    @Resource
    private StringRedisTemplate stringRedisTemplate;

    @Resource
    private IUserService userService;

    @Override
    public Result follow(Long followUserId, Boolean isFollow) {
        //1. 获取当前登录用户
        Long userId = UserHolder.getUser().getId();
        if (userId.equals(followUserId)) {
            return Result.fail("不能关注自己");
        }
        String key = "follows:" + userId;
        //2. 判断是否关注
        if (isFollow){
            //3. 关注请求保持幂等；数据库唯一约束处理并发下的最终竞争
            boolean isSuccess = query().eq("user_id", userId)
                    .eq("follow_user_id", followUserId).count() > 0;
            if (!isSuccess) {
                Follow follow = new Follow();
                follow.setUserId(userId);
                follow.setFollowUserId(followUserId);
                try {
                    isSuccess = save(follow);
                } catch (DuplicateKeyException duplicateKeyException) {
                    isSuccess = true;
                }
            }
            if (isSuccess){
                //数据库已存在关系时也刷新 Redis 投影
                stringRedisTemplate.opsForZSet().add(key, followUserId.toString(), System.currentTimeMillis());
            }
        }else{
            //4. 取消关注
            boolean isSuccess = remove(new QueryWrapper<Follow>()
                    .eq("user_id", userId).eq("follow_user_id", followUserId));
            if (isSuccess) {
                stringRedisTemplate.opsForZSet().remove(key, followUserId.toString());
            }
        }
        return Result.ok();
    }

    @Override
    public Result isFollow(Long followUserId) {
        //1. 获取当前登录用户
        Long userId = UserHolder.getUser().getId();
        //2. 查询是否关注
        Integer count = query().eq("user_id", userId).eq("follow_user_id", followUserId).count();
        return Result.ok(count > 0);
    }

    @Override
    public Result common(Long id) {
        //1. 获取当前登录用户
        Long userId = UserHolder.getUser().getId();
        String key = "follows:" + userId;
        //2. 使用与关注写入一致的 ZSet 结构求交集
        String key2 = "follows:" + id;
        Set<String> currentUserFollows = stringRedisTemplate.opsForZSet().range(key, 0, -1);
        Set<String> targetUserFollows = stringRedisTemplate.opsForZSet().range(key2, 0, -1);
        if (currentUserFollows == null || currentUserFollows.isEmpty()
                || targetUserFollows == null || targetUserFollows.isEmpty()) {
            return Result.ok(Collections.emptyList());
        }
        Set<String> intersect = new HashSet<>(currentUserFollows);
        intersect.retainAll(targetUserFollows);
        //3. 解析id集合
        if (intersect.isEmpty()) {
            //无交集
            return Result.ok(Collections.emptyList());
        }
        List<Long> ids = intersect.stream().map(Long::valueOf).collect(Collectors.toList());
        //将 Redis 返回的 String 类型 ID 集合转换为 Long 类型的列表
        List<UserDTO> users = userService.listByIds(ids)
                .stream()
                .map(user -> BeanUtil.copyProperties(user, UserDTO.class))
                .collect(Collectors.toList());
        return Result.ok(users);
    }
}
