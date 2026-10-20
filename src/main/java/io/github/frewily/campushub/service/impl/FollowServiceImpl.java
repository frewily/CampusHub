package io.github.frewily.campushub.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import io.github.frewily.campushub.dto.Result;
import io.github.frewily.campushub.entity.Follow;
import io.github.frewily.campushub.exception.BusinessException;
import io.github.frewily.campushub.exception.ErrorCode;
import io.github.frewily.campushub.mapper.FollowMapper;
import io.github.frewily.campushub.service.IFollowService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import io.github.frewily.campushub.utils.UserHolder;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import javax.annotation.Resource;

@Service
public class FollowServiceImpl extends ServiceImpl<FollowMapper, Follow> implements IFollowService {

    @Resource
    private StringRedisTemplate stringRedisTemplate;

    @Override
    public Result follow(Long followUserId, Boolean isFollow) {
        Long userId = UserHolder.getUser().getId();
        if (userId.equals(followUserId)) {
            throw new BusinessException(ErrorCode.CONFLICT, "不能关注自己");
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

}
