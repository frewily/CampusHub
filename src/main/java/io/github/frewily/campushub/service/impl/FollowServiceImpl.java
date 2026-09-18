package io.github.frewily.campushub.service.impl;

import cn.hutool.core.bean.BeanUtil;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import io.github.frewily.campushub.dto.Result;
import io.github.frewily.campushub.dto.UserDTO;
import io.github.frewily.campushub.entity.Follow;
import io.github.frewily.campushub.entity.User;
import io.github.frewily.campushub.mapper.FollowMapper;
import io.github.frewily.campushub.service.IFollowService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import io.github.frewily.campushub.service.IUserService;
import io.github.frewily.campushub.utils.UserHolder;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import javax.annotation.Resource;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

@Service
public class FollowServiceImpl extends ServiceImpl<FollowMapper, Follow> implements IFollowService {

    @Resource
    private StringRedisTemplate stringRedisTemplate;

    @Resource
    private IUserService userService;

    @Override
    public Result follow(Long followUserId, Boolean isFollow) {
        Long userId = UserHolder.getUser().getId();
        if (userId.equals(followUserId)) {
            return Result.fail("不能关注自己");
        }
        String key = "follows:" + userId;
        if (isFollow){
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
                // 数据库已存在关系时也刷新 Redis 投影。
                stringRedisTemplate.opsForZSet().add(key, followUserId.toString(), System.currentTimeMillis());
            }
        }else{
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
        Long userId = UserHolder.getUser().getId();
        Integer count = query().eq("user_id", userId).eq("follow_user_id", followUserId).count();
        return Result.ok(count > 0);
    }

    @Override
    public Result common(Long id) {
        Long userId = UserHolder.getUser().getId();
        String key = "follows:" + userId;
        String key2 = "follows:" + id;
        Set<String> currentUserFollows = stringRedisTemplate.opsForZSet().range(key, 0, -1);
        Set<String> targetUserFollows = stringRedisTemplate.opsForZSet().range(key2, 0, -1);
        if (currentUserFollows == null || currentUserFollows.isEmpty()
                || targetUserFollows == null || targetUserFollows.isEmpty()) {
            return Result.ok(Collections.emptyList());
        }
        Set<String> intersect = new HashSet<>(currentUserFollows);
        intersect.retainAll(targetUserFollows);
        if (intersect.isEmpty()) {
            // 无共同关注。
            return Result.ok(Collections.emptyList());
        }
        List<Long> ids = intersect.stream().map(Long::valueOf).collect(Collectors.toList());
        // Redis member 使用字符串 ID，查询用户前转换为 Long。
        List<UserDTO> users = userService.listByIds(ids)
                .stream()
                .map(user -> BeanUtil.copyProperties(user, UserDTO.class))
                .collect(Collectors.toList());
        return Result.ok(users);
    }
}
