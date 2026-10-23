package io.github.frewily.campushub.identity.application;

import io.github.frewily.campushub.dto.UserDTO;
import io.github.frewily.campushub.dto.response.UserInfoResponse;

public interface UserProfileReader {
    UserDTO findUserById(Long userId);
    UserInfoResponse findInfoById(Long userId);
}
