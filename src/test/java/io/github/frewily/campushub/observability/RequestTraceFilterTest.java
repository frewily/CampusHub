package io.github.frewily.campushub.observability;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.web.servlet.HandlerMapping;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.UUID;

import static io.github.frewily.campushub.observability.RequestTraceFilter.REQUEST_ID_HEADER;
import static io.github.frewily.campushub.observability.RequestTraceFilter.REQUEST_ID_MDC_KEY;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RequestTraceFilterTest {

    private final RequestTraceFilter filter = new RequestTraceFilter();

    @AfterEach
    void clearMdc() {
        MDC.clear();
    }

    @Test
    void shouldGenerateFreshIdAndExposeItInResponseAndChainMdc() throws Exception {
        MockHttpServletRequest firstRequest = requestWithClientId("forged-client-id");
        MockHttpServletResponse firstResponse = new MockHttpServletResponse();
        String[] firstChainId = new String[1];
        filter.doFilter(firstRequest, firstResponse, (request, response) -> {
            firstChainId[0] = MDC.get(REQUEST_ID_MDC_KEY);
            assertEquals(firstChainId[0], ((MockHttpServletResponse) response).getHeader(REQUEST_ID_HEADER));
        });

        MockHttpServletRequest secondRequest = requestWithClientId("forged-client-id");
        MockHttpServletResponse secondResponse = new MockHttpServletResponse();
        String[] secondChainId = new String[1];
        filter.doFilter(secondRequest, secondResponse, (request, response) -> {
            secondChainId[0] = MDC.get(REQUEST_ID_MDC_KEY);
            assertEquals(secondChainId[0], ((MockHttpServletResponse) response).getHeader(REQUEST_ID_HEADER));
        });

        assertNotNull(firstChainId[0]);
        assertNotNull(secondChainId[0]);
        assertDoesNotUseClientId(firstChainId[0]);
        assertDoesNotUseClientId(secondChainId[0]);
        assertNotEquals(firstChainId[0], secondChainId[0]);
        assertNull(MDC.get(REQUEST_ID_MDC_KEY));
    }

    @Test
    void shouldRestoreExistingMdcAfterSuccessfulDispatch() throws Exception {
        MDC.put("requestId", "outer-request");
        MDC.put("other", "outer-value");

        filter.doFilter(new MockHttpServletRequest(), new MockHttpServletResponse(), (request, response) -> {
            assertTrue(isUuid(MDC.get(REQUEST_ID_MDC_KEY)));
            assertEquals("outer-value", MDC.get("other"));
        });

        assertEquals("outer-request", MDC.get(REQUEST_ID_MDC_KEY));
        assertEquals("outer-value", MDC.get("other"));
    }

    @Test
    void shouldRestoreExistingMdcAndPropagateChainError() {
        MDC.put("requestId", "outer-request");
        MDC.put("other", "outer-value");
        Logger logger = (Logger) LoggerFactory.getLogger(RequestTraceFilter.class);
        Level previousLevel = logger.getLevel();
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        logger.setLevel(Level.INFO);
        logger.addAppender(appender);

        try {
            String exceptionMessage = "private-exception-message";
            IOException expected = new IOException(exceptionMessage);
            IOException actual = assertThrows(IOException.class, () -> filter.doFilter(
                    new MockHttpServletRequest(), new MockHttpServletResponse(), (request, response) -> {
                        assertTrue(isUuid(MDC.get(REQUEST_ID_MDC_KEY)));
                        throw expected;
                    }));

            assertEquals(expected, actual);
            assertEquals("outer-request", MDC.get(REQUEST_ID_MDC_KEY));
            assertEquals("outer-value", MDC.get("other"));
            assertEquals(1, appender.list.size());
            ILoggingEvent event = appender.list.get(0);
            assertTrue(event.getFormattedMessage().contains("dispatchFailed=true"));
            assertFalse(event.getFormattedMessage().contains(exceptionMessage));
            assertTrue(isUuid(event.getMDCPropertyMap().get(REQUEST_ID_MDC_KEY)));
        } finally {
            logger.detachAppender(appender);
            logger.setLevel(previousLevel);
            appender.stop();
        }
    }

    @Test
    void shouldCleanRequestIdAfterSuccessAndErrorWhenNoPriorMdcExists() throws Exception {
        filter.doFilter(new MockHttpServletRequest(), new MockHttpServletResponse(), (request, response) ->
                assertTrue(isUuid(MDC.get(REQUEST_ID_MDC_KEY))));
        assertNull(MDC.get(REQUEST_ID_MDC_KEY));

        assertThrows(IOException.class, () -> filter.doFilter(
                new MockHttpServletRequest(), new MockHttpServletResponse(), (request, response) -> {
                    assertTrue(isUuid(MDC.get(REQUEST_ID_MDC_KEY)));
                    throw new IOException("expected failure");
                }));
        assertNull(MDC.get(REQUEST_ID_MDC_KEY));
        assertNull(MDC.getCopyOfContextMap());
    }

    @Test
    void shouldLogOnlySafeMethodRouteStatusAndDuration() throws Exception {
        Logger logger = (Logger) LoggerFactory.getLogger(RequestTraceFilter.class);
        Level previousLevel = logger.getLevel();
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        logger.setLevel(Level.INFO);
        logger.addAppender(appender);

        try {
            MockHttpServletRequest request = new MockHttpServletRequest();
            request.setMethod("BREW");
            request.setRequestURI("/shops/raw-path-secret");
            request.setQueryString("token=query-secret");
            request.addHeader("Authorization", "header-secret");
            request.addHeader(REQUEST_ID_HEADER, "forged-client-id");
            request.addHeader("X-User-Id", "user-id-secret");
            request.addHeader("X-Phone", "phone-secret");
            request.setContent("body-secret".getBytes(StandardCharsets.UTF_8));
            request.setAttribute(HandlerMapping.BEST_MATCHING_PATTERN_ATTRIBUTE, "/shops/{shopId}");
            MockHttpServletResponse response = new MockHttpServletResponse();

            filter.doFilter(request, response, (ignoredRequest, ignoredResponse) -> response.setStatus(202));

            assertEquals(1, appender.list.size());
            ILoggingEvent event = appender.list.get(0);
            String message = event.getFormattedMessage();
            assertTrue(message.contains("method=UNKNOWN"));
            assertTrue(message.contains("route=/shops/{shopId}"));
            assertTrue(message.contains("status=202"));
            assertTrue(message.contains("durationMs="));
            assertFalse(message.contains("raw-path-secret"));
            assertFalse(message.contains("query-secret"));
            assertFalse(message.contains("header-secret"));
            assertFalse(message.contains("body-secret"));
            assertFalse(message.contains("forged-client-id"));
            assertFalse(message.contains("user-id-secret"));
            assertFalse(message.contains("phone-secret"));
            assertTrue(isUuid(event.getMDCPropertyMap().get(REQUEST_ID_MDC_KEY)));
        } finally {
            logger.detachAppender(appender);
            logger.setLevel(previousLevel);
            appender.stop();
        }
    }

    private MockHttpServletRequest requestWithClientId(String clientId) {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader(REQUEST_ID_HEADER, clientId);
        return request;
    }

    private void assertDoesNotUseClientId(String requestId) {
        assertNotEquals("forged-client-id", requestId);
        assertTrue(isUuid(requestId));
    }

    private boolean isUuid(String value) {
        if (value == null) {
            return false;
        }
        try {
            UUID.fromString(value);
            return true;
        } catch (IllegalArgumentException ignored) {
            return false;
        }
    }
}
