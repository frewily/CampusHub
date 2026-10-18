package io.github.frewily.campushub.engagement.application;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZoneOffset;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class CheckInServiceTest {
    private CheckInStore store;
    private CheckInService service;
    private final LocalDate today = LocalDate.of(2026, 10, 18);

    @BeforeEach
    void setUp() {
        store = mock(CheckInStore.class);
        service = new CheckInService(store, Clock.fixed(Instant.parse("2026-10-18T04:00:00Z"), ZoneOffset.UTC));
    }

    @Test
    void signUsesOnlyTheProvidedIdentityAndCurrentDate() {
        service.sign(9007199254740993L);
        verify(store).markSigned(9007199254740993L, today);
        verifyNoMoreInteractions(store);
    }

    @ParameterizedTest
    @CsvSource({"0,0", "1,1", "2,0", "7,3", "23,3", "262143,18"})
    void countsOnlyTrailingSignedDaysIncludingToday(long bits, int count) {
        when(store.signedDays(21L, today)).thenReturn(bits);
        assertEquals(count, service.signCount(21L));
        verify(store).signedDays(21L, today);
        verifyNoMoreInteractions(store);
    }

    @Test
    void usesTheClockZoneRatherThanAssumingUtc() {
        Clock clock = Clock.fixed(Instant.parse("2026-10-31T16:30:00Z"), ZoneId.of("Asia/Shanghai"));
        new CheckInService(store, clock).sign(21L);
        verify(store).markSigned(21L, LocalDate.of(2026, 11, 1));
    }

    @Test
    void capturesTheDateOnceEvenAcrossMidnight() {
        Clock clock = new Clock() {
            private int calls;
            @Override public ZoneId getZone() { return ZoneOffset.UTC; }
            @Override public Clock withZone(ZoneId zone) { throw new UnsupportedOperationException(); }
            @Override public Instant instant() {
                assertEquals(0, calls++, "each operation must capture its date once");
                return Instant.parse("2026-10-31T23:59:59Z");
            }
        };
        new CheckInService(store, clock).signCount(21L);
        verify(store).signedDays(21L, LocalDate.of(2026, 10, 31));
    }

    @Test
    void storeFailuresAreNotReportedAsSuccessfulSigning() {
        doThrow(new IllegalStateException("synthetic store failure")).when(store).markSigned(21L, today);
        assertThrows(IllegalStateException.class, () -> service.sign(21L));
    }
}
