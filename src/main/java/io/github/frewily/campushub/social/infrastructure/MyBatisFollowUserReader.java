package io.github.frewily.campushub.social.infrastructure;

import cn.hutool.core.bean.BeanUtil;
import io.github.frewily.campushub.dto.UserDTO;
import io.github.frewily.campushub.mapper.UserMapper;
import io.github.frewily.campushub.social.application.FollowUserReader;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.stream.Collectors;

/** Reads the existing user rows without adding order or account-state filtering. */
@Component
public class MyBatisFollowUserReader implements FollowUserReader {
    private final UserMapper users;

    public MyBatisFollowUserReader(UserMapper users) { this.users = users; }

    @Override
    public List<UserDTO> findByIds(List<Long> ids) {
        return users.selectBatchIds(ids).stream().map(user -> BeanUtil.copyProperties(user, UserDTO.class)).collect(Collectors.toList());
    }
}
