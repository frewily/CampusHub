package io.github.frewily.campushub.content.infrastructure;

import cn.hutool.core.bean.BeanUtil;
import cn.hutool.core.util.StrUtil;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import io.github.frewily.campushub.content.application.BlogUserReader;
import io.github.frewily.campushub.dto.UserDTO;
import io.github.frewily.campushub.entity.User;
import io.github.frewily.campushub.mapper.UserMapper;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.stream.Collectors;

@Component
public class MyBatisBlogUserReader implements BlogUserReader {
    private final UserMapper users;

    public MyBatisBlogUserReader(UserMapper users) { this.users = users; }

    @Override
    public UserDTO findAuthor(Long userId) {
        User user = users.selectById(userId);
        return user == null ? null : BeanUtil.copyProperties(user, UserDTO.class);
    }

    @Override
    public List<UserDTO> findLikers(List<Long> ids) {
        // IDs have already been parsed to Long by the caller; retain the original FIELD ordering.
        String idStr = StrUtil.join(",", ids);
        return users.selectList(new QueryWrapper<User>().in("id", ids)
                .last("ORDER BY FIELD(id," + idStr + ")")).stream()
                .map(user -> BeanUtil.copyProperties(user, UserDTO.class))
                .collect(Collectors.toList());
    }
}
