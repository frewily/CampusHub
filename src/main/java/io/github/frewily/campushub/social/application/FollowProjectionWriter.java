package io.github.frewily.campushub.social.application;

public interface FollowProjectionWriter {
    void add(Long actorId, Long targetId, long timestamp);
    void remove(Long actorId, Long targetId);
}
