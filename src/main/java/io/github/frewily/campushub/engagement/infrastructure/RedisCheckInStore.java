package io.github.frewily.campushub.engagement.infrastructure;

import io.github.frewily.campushub.engagement.application.CheckInStore;
import org.springframework.data.redis.connection.BitFieldSubCommands;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.List;

import static io.github.frewily.campushub.utils.RedisConstants.USER_SIGN_KEY;

@Component
public class RedisCheckInStore implements CheckInStore {
    private static final DateTimeFormatter MONTH_FORMAT = DateTimeFormatter.ofPattern(":yyyyMM");

    private final StringRedisTemplate redis;

    public RedisCheckInStore(StringRedisTemplate redis) {
        this.redis = redis;
    }

    @Override
    public void markSigned(Long userId, LocalDate date) {
        redis.opsForValue().setBit(key(userId, date), date.getDayOfMonth() - 1, true);
    }

    @Override
    public long signedDays(Long userId, LocalDate date) {
        List<Long> result = redis.opsForValue().bitField(
                key(userId, date),
                BitFieldSubCommands.create()
                        .get(BitFieldSubCommands.BitFieldType.unsigned(date.getDayOfMonth()))
                        .valueAt(0)
        );
        if (result == null || result.isEmpty() || result.get(0) == null) {
            return 0L;
        }
        return result.get(0);
    }

    private String key(Long userId, LocalDate date) {
        return USER_SIGN_KEY + userId + date.format(MONTH_FORMAT);
    }
}
