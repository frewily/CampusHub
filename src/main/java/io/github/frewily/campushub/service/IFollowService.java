package io.github.frewily.campushub.service;

import io.github.frewily.campushub.dto.Result;
import io.github.frewily.campushub.entity.Follow;
import com.baomidou.mybatisplus.extension.service.IService;

public interface IFollowService extends IService<Follow> {

    /**
     * 关注或取关
     * @param followUserId
     * @param isFollow
     * @return
     */
    Result follow(Long followUserId, Boolean isFollow);

}
