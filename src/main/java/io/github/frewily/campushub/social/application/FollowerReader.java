package io.github.frewily.campushub.social.application;

import java.util.List;

/** Relationship facts used by publication, not the Redis follows projection. */
public interface FollowerReader {
    List<Long> findFollowerIds(Long authorId);
}
