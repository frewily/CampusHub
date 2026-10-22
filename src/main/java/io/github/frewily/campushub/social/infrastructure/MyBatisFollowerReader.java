package io.github.frewily.campushub.social.infrastructure;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import io.github.frewily.campushub.entity.Follow;
import io.github.frewily.campushub.mapper.FollowMapper;
import io.github.frewily.campushub.social.application.FollowerReader;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.stream.Collectors;

@Component
public class MyBatisFollowerReader implements FollowerReader {
    private final FollowMapper mapper;

    public MyBatisFollowerReader(FollowMapper mapper) { this.mapper = mapper; }

    @Override
    public List<Long> findFollowerIds(Long authorId) {
        return mapper.selectList(new QueryWrapper<Follow>().eq("follow_user_id", authorId))
                .stream().map(Follow::getUserId).collect(Collectors.toList());
    }
}
