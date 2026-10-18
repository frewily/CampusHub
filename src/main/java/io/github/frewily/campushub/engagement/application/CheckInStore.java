package io.github.frewily.campushub.engagement.application;

import java.time.LocalDate;

public interface CheckInStore {
    void markSigned(Long userId, LocalDate date);

    long signedDays(Long userId, LocalDate date);
}
