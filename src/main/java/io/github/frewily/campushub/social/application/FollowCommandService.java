package io.github.frewily.campushub.social.application;

import io.github.frewily.campushub.exception.BusinessException;
import io.github.frewily.campushub.exception.ErrorCode;
import java.time.Clock;

public class FollowCommandService {
    private final FollowCommandRepository repository;
    private final FollowProjectionWriter projection;
    private final Clock clock;

    public FollowCommandService(FollowCommandRepository repository, FollowProjectionWriter projection, Clock clock) {
        this.repository = repository;
        this.projection = projection;
        this.clock = clock;
    }

    public void follow(Long actorId, Long targetId, Boolean isFollow) {
        if (actorId.equals(targetId)) {
            throw new BusinessException(ErrorCode.CONFLICT, "不能关注自己");
        }
        if (isFollow) {
            boolean success = repository.exists(actorId, targetId);
            if (!success) {
                success = repository.create(actorId, targetId);
            }
            if (success) {
                // Repeated follow also refreshes the legacy projection score.
                projection.add(actorId, targetId, clock.millis());
            }
        } else if (repository.delete(actorId, targetId)) {
            projection.remove(actorId, targetId);
        }
    }
}
