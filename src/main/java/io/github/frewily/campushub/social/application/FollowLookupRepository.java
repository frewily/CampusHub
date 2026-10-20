package io.github.frewily.campushub.social.application;

public interface FollowLookupRepository {
    Integer count(Long actorId, Long targetId);
}
