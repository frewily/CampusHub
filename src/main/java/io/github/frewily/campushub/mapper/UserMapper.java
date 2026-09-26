package io.github.frewily.campushub.mapper;

import io.github.frewily.campushub.entity.User;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Param;

public interface UserMapper extends BaseMapper<User> {

    @Insert("INSERT IGNORE INTO tb_user_role (user_id, role) VALUES (#{userId}, 'USER')")
    int insertDefaultUserRole(@Param("userId") Long userId);
}
