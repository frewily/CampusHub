package io.github.frewily.campushub.social.application;

import io.github.frewily.campushub.dto.UserDTO;

import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

public class FollowQueryService {
    private final FollowLookupRepository repository;
    private final FollowProjectionReader projection;
    private final FollowUserReader users;

    public FollowQueryService(FollowLookupRepository repository, FollowProjectionReader projection, FollowUserReader users) {
        this.repository = repository;
        this.projection = projection;
        this.users = users;
    }

    public boolean isFollow(Long actorId, Long targetId) {
        return repository.count(actorId, targetId) > 0;
    }

    public List<UserDTO> common(Long actorId, Long targetId) {
        Set<String> current = projection.following(actorId);
        Set<String> target = projection.following(targetId);
        if (current == null || current.isEmpty() || target == null || target.isEmpty()) {
            return Collections.emptyList();
        }
        Set<String> intersect = new HashSet<>(current);
        intersect.retainAll(target);
        if (intersect.isEmpty()) {
            return Collections.emptyList();
        }
        List<Long> ids = intersect.stream().map(Long::valueOf).collect(Collectors.toList());
        return users.findByIds(ids);
    }
}
