package io.github.frewily.campushub.social.application;

import java.util.Set;

public interface FollowProjectionReader {
    Set<String> following(Long userId);
}
