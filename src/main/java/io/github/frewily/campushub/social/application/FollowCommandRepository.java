package io.github.frewily.campushub.social.application;

public interface FollowCommandRepository {
    boolean exists(Long actorId, Long targetId);
    /** A duplicate unique relationship is treated as an existing successful relationship. */
    boolean create(Long actorId, Long targetId);
    boolean delete(Long actorId, Long targetId);
}
