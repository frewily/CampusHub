package io.github.frewily.campushub.social.infrastructure;

import cn.hutool.core.bean.BeanUtil;
import io.github.frewily.campushub.dto.UserDTO;
import io.github.frewily.campushub.service.IUserService;
import io.github.frewily.campushub.social.application.FollowUserReader;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.stream.Collectors;

/** Transitional bridge to the existing user service, preserving its row selection and DTO mapping. */
@Component
public class LegacyFollowUserReader implements FollowUserReader {
    private final IUserService users;

    public LegacyFollowUserReader(IUserService users) { this.users = users; }

    @Override
    public List<UserDTO> findByIds(List<Long> ids) {
        return users.listByIds(ids).stream().map(user -> BeanUtil.copyProperties(user, UserDTO.class)).collect(Collectors.toList());
    }
}
