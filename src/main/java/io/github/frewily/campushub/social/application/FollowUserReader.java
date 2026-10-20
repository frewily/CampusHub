package io.github.frewily.campushub.social.application;

import io.github.frewily.campushub.dto.UserDTO;

import java.util.List;

public interface FollowUserReader {
    List<UserDTO> findByIds(List<Long> ids);
}
