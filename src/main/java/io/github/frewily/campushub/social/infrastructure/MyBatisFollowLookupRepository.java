package io.github.frewily.campushub.social.infrastructure;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import io.github.frewily.campushub.entity.Follow;
import io.github.frewily.campushub.mapper.FollowMapper;
import io.github.frewily.campushub.social.application.FollowLookupRepository;
import org.springframework.stereotype.Component;

@Component
public class MyBatisFollowLookupRepository implements FollowLookupRepository {
    private final FollowMapper mapper;

    public MyBatisFollowLookupRepository(FollowMapper mapper) { this.mapper = mapper; }

    @Override
    public Integer count(Long actorId, Long targetId) {
        return mapper.selectCount(new QueryWrapper<Follow>().eq("user_id", actorId).eq("follow_user_id", targetId));
    }
}
