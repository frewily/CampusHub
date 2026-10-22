package io.github.frewily.campushub.service.impl;

import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import io.github.frewily.campushub.entity.Follow;
import io.github.frewily.campushub.mapper.FollowMapper;
import io.github.frewily.campushub.service.IFollowService;
import org.springframework.stereotype.Service;

/** Legacy follower lookup bridge for publication; public follow use cases live in social. */
@Service
public class FollowServiceImpl extends ServiceImpl<FollowMapper, Follow> implements IFollowService {
}
