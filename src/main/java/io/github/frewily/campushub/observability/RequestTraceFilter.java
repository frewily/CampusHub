package io.github.frewily.campushub.observability;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.web.servlet.HandlerMapping;

import javax.servlet.FilterChain;
import javax.servlet.ServletException;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.Map;
import java.util.UUID;

/**
 * Adds a server-generated request identifier and logs a bounded request summary.
 * The status is the value observed when this dispatch ends, not a guarantee of the final
 * client status after an exception or async completion. The duration measures this servlet
 * dispatch only; it does not measure async completion.
 */
public class RequestTraceFilter extends OncePerRequestFilter {

    public static final String REQUEST_ID_HEADER = "X-Request-Id";
    public static final String REQUEST_ID_MDC_KEY = "requestId";

    private static final Logger log = LoggerFactory.getLogger(RequestTraceFilter.class);

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        String requestId = UUID.randomUUID().toString();
        Map<String, String> previousMdc = MDC.getCopyOfContextMap();
        long startedAt = System.nanoTime();

        response.setHeader(REQUEST_ID_HEADER, requestId);
        MDC.put(REQUEST_ID_MDC_KEY, requestId);
        boolean dispatchFailed = false;
        try {
            filterChain.doFilter(request, response);
        } catch (IOException | ServletException | RuntimeException exception) {
            dispatchFailed = true;
            throw exception;
        } finally {
            try {
                long durationMs = (System.nanoTime() - startedAt) / 1_000_000L;
                log.info("request method={} route={} status={} durationMs={} dispatchFailed={}",
                        safeMethod(request.getMethod()), routeTemplate(request), response.getStatus(),
                        durationMs, dispatchFailed);
            } finally {
                if (previousMdc == null) {
                    MDC.clear();
                } else {
                    MDC.setContextMap(previousMdc);
                }
            }
        }
    }

    private String safeMethod(String method) {
        if ("GET".equals(method) || "POST".equals(method) || "PUT".equals(method)
                || "PATCH".equals(method) || "DELETE".equals(method)
                || "HEAD".equals(method) || "OPTIONS".equals(method)) {
            return method;
        }
        return "UNKNOWN";
    }

    private String routeTemplate(HttpServletRequest request) {
        Object pattern = request.getAttribute(HandlerMapping.BEST_MATCHING_PATTERN_ATTRIBUTE);
        return pattern instanceof String ? (String) pattern : "UNMATCHED";
    }
}
