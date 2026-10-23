package io.github.frewily.campushub.content.application;

import io.github.frewily.campushub.dto.UserDTO;

import java.util.List;

/** User display data needed by the existing post reads, not an authentication or account-write API. */
public interface BlogUserReader {
    UserDTO findAuthor(Long userId);

    List<UserDTO> findLikers(List<Long> ids);
}
