package io.github.frewily.campushub.identity.infrastructure;

import cn.hutool.core.util.RandomUtil;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import io.github.frewily.campushub.entity.User;
import io.github.frewily.campushub.identity.application.LoginAccount;
import io.github.frewily.campushub.identity.application.LoginAccountRepository;
import io.github.frewily.campushub.mapper.UserMapper;
import org.springframework.stereotype.Component;

import static io.github.frewily.campushub.utils.SystemConstants.USER_NICK_NAME_PREFIX;

@Component
public class MyBatisLoginAccountRepository implements LoginAccountRepository {
    private final UserMapper users;

    public MyBatisLoginAccountRepository(UserMapper users) { this.users = users; }

    @Override
    public LoginAccount findByPhone(String phone) {
        return account(users.selectOne(new QueryWrapper<User>().eq("phone", phone)));
    }

    @Override
    public LoginAccount createWithPhone(String phone) {
        User user = new User();
        user.setPhone(phone);
        user.setNickName(USER_NICK_NAME_PREFIX + RandomUtil.randomString(10));
        user.setStatus("ACTIVE");
        // Retain the original ignored affected-row results and the surrounding login transaction.
        users.insert(user);
        users.insertDefaultUserRole(user.getId());
        return account(user);
    }

    private LoginAccount account(User user) {
        return user == null ? null : new LoginAccount(user.getId(), user.getNickName(), user.getIcon(), user.getStatus());
    }
}
