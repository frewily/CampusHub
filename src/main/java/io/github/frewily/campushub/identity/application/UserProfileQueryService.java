package io.github.frewily.campushub.identity.application;

import io.github.frewily.campushub.dto.UserDTO;
import io.github.frewily.campushub.dto.response.UserInfoResponse;

/** Preserves the two independent legacy lookups and their missing-row semantics. */
public class UserProfileQueryService {
    private final UserProfileReader reader;

    public UserProfileQueryService(UserProfileReader reader) { this.reader = reader; }

    public UserDTO queryUserById(Long userId) { return reader.findUserById(userId); }
    public UserInfoResponse info(Long userId) { return reader.findInfoById(userId); }
}
