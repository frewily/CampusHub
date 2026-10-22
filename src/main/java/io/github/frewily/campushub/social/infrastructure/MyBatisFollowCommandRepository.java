package io.github.frewily.campushub.social.infrastructure;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import io.github.frewily.campushub.entity.Follow;
import io.github.frewily.campushub.mapper.FollowMapper;
import io.github.frewily.campushub.social.application.FollowCommandRepository;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Component;

@Component
public class MyBatisFollowCommandRepository implements FollowCommandRepository {
    private final FollowMapper mapper;

    public MyBatisFollowCommandRepository(FollowMapper mapper) { this.mapper = mapper; }

    private QueryWrapper<Follow> relationship(Long actorId, Long targetId) {
        return new QueryWrapper<Follow>().eq("user_id", actorId).eq("follow_user_id", targetId);
    }

    @Override
    public boolean exists(Long actorId, Long targetId) {
        return mapper.selectCount(relationship(actorId, targetId)) > 0;
    }

    @Override
    public boolean create(Long actorId, Long targetId) {
        Follow follow = new Follow();
        follow.setUserId(actorId);
        follow.setFollowUserId(targetId);
        try {
            return mapper.insert(follow) > 0;
        } catch (DuplicateKeyException duplicateKeyException) {
            return true;
        }
    }

    @Override
    public boolean delete(Long actorId, Long targetId) {
        return mapper.delete(relationship(actorId, targetId)) > 0;
    }
}
