package io.github.frewily.campushub.engagement.application;

import java.time.Clock;
import java.time.LocalDate;

public class CheckInService {
    private final CheckInStore store;
    private final Clock clock;

    public CheckInService(CheckInStore store, Clock clock) {
        this.store = store;
        this.clock = clock;
    }

    public void sign(Long userId) {
        LocalDate today = LocalDate.now(clock);
        store.markSigned(userId, today);
    }

    public int signCount(Long userId) {
        LocalDate today = LocalDate.now(clock);
        long signedDays = store.signedDays(userId, today);
        int count = 0;
        while ((signedDays & 1L) == 1L) {
            count++;
            signedDays >>>= 1;
        }
        return count;
    }
}
