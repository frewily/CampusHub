package io.github.frewily.campushub.identity.application;

import io.github.frewily.campushub.dto.UserDTO;
import io.github.frewily.campushub.dto.response.UserInfoResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class UserProfileQueryServiceTest {
    private UserProfileReader reader;
    private UserProfileQueryService service;

    @BeforeEach
    void setUp() {
        reader = mock(UserProfileReader.class);
        service = new UserProfileQueryService(reader);
    }

    @Test
    void userLookupReturnsTheReaderValueWithoutAdditionalPolicy() {
        UserDTO dto = new UserDTO();
        dto.setId(9007199254740993L);
        when(reader.findUserById(9007199254740993L)).thenReturn(dto);

        assertSame(dto, service.queryUserById(9007199254740993L));
        when(reader.findUserById(5L)).thenReturn(null);
        assertNull(service.queryUserById(5L));
        verify(reader).findUserById(9007199254740993L);
        verify(reader).findUserById(5L);
        verifyNoMoreInteractions(reader);
    }

    @Test
    void profileLookupPassesThroughMissingRowsAndReaderFailures() {
        assertNull(service.info(2L));
        RuntimeException failure = new IllegalStateException("synthetic reader failure");
        when(reader.findInfoById(3L)).thenThrow(failure);
        assertSame(failure, assertThrows(RuntimeException.class, () -> service.info(3L)));
        verify(reader).findInfoById(2L);
        verify(reader).findInfoById(3L);
        verifyNoMoreInteractions(reader);
    }

    @Test
    void profileLookupReturnsTheReaderDtoDirectly() {
        UserInfoResponse response = new UserInfoResponse();
        when(reader.findInfoById(4L)).thenReturn(response);

        assertSame(response, service.info(4L));
        verify(reader).findInfoById(4L);
        verifyNoMoreInteractions(reader);
    }
}
