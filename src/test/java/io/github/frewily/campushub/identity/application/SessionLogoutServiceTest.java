package io.github.frewily.campushub.identity.application;

import io.github.frewily.campushub.exception.BusinessException;
import io.github.frewily.campushub.exception.ErrorCode;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class SessionLogoutServiceTest {
    private final SessionTerminator terminator = mock(SessionTerminator.class);
    private final SessionLogoutService service = new SessionLogoutService(terminator);

    @Test void trimsTokenBeforeTerminating() {
        service.logout("  synthetic-token \t");
        verify(terminator).terminate("synthetic-token");
    }

    @Test void rejectsNullAndTrimmedEmptyTokensWithoutCallingPort() {
        for (String token : new String[]{null, "", " \t\n"}) {
            BusinessException error = assertThrows(BusinessException.class, () -> service.logout(token));
            assertEquals(ErrorCode.AUTHENTICATION_FAILED, error.getErrorCode());
            assertEquals("认证信息缺失", error.getMessage());
        }
        verifyNoInteractions(terminator);
    }

    @Test void terminationFailureIsNotReportedAsSuccess() {
        IllegalStateException failure = new IllegalStateException("synthetic termination failure");
        doThrow(failure).when(terminator).terminate("synthetic-token");
        assertSame(failure, assertThrows(IllegalStateException.class, () -> service.logout(" synthetic-token ")));
        verify(terminator).terminate("synthetic-token");
        verifyNoMoreInteractions(terminator);
    }
}
