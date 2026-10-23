package io.github.frewily.campushub.identity.infrastructure;

import cn.hutool.core.bean.BeanUtil;
import io.github.frewily.campushub.dto.ApiModelMapper;
import io.github.frewily.campushub.dto.UserDTO;
import io.github.frewily.campushub.dto.response.UserInfoResponse;
import io.github.frewily.campushub.entity.User;
import io.github.frewily.campushub.entity.UserInfo;
import io.github.frewily.campushub.identity.application.UserProfileReader;
import io.github.frewily.campushub.mapper.UserMapper;
import io.github.frewily.campushub.mapper.UserInfoMapper;
import org.springframework.stereotype.Component;

@Component
public class MyBatisUserProfileReader implements UserProfileReader {
    private final UserMapper users;
    private final UserInfoMapper infos;

    public MyBatisUserProfileReader(UserMapper users, UserInfoMapper infos) {
        this.users = users;
        this.infos = infos;
    }

    @Override
    public UserDTO findUserById(Long userId) {
        User user = users.selectById(userId);
        return user == null ? null : BeanUtil.copyProperties(user, UserDTO.class);
    }

    @Override
    public UserInfoResponse findInfoById(Long userId) {
        UserInfo info = infos.selectById(userId);
        return info == null ? null : ApiModelMapper.toUserInfoResponse(info);
    }
}
