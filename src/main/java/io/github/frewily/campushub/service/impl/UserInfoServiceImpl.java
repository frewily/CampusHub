package io.github.frewily.campushub.service.impl;

import io.github.frewily.campushub.entity.UserInfo;
import io.github.frewily.campushub.mapper.UserInfoMapper;
import io.github.frewily.campushub.service.IUserInfoService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import org.springframework.stereotype.Service;

@Service
public class UserInfoServiceImpl extends ServiceImpl<UserInfoMapper, UserInfo> implements IUserInfoService {

}
