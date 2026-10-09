package io.github.frewily.campushub.tools;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;
import java.net.InetSocketAddress;
import static org.junit.jupiter.api.Assertions.*;

class HealthProbeTest {
    @Test void accepts200ButRejectsDownRedirectsAndNonHttp() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/up", exchange -> { exchange.sendResponseHeaders(200, -1); exchange.close(); });
        server.createContext("/down", exchange -> { exchange.sendResponseHeaders(503, -1); exchange.close(); });
        server.createContext("/redirect", exchange -> { exchange.getResponseHeaders().add("Location", "/up"); exchange.sendResponseHeaders(302, -1); exchange.close(); });
        server.start();
        try {
            String base = "http://127.0.0.1:" + server.getAddress().getPort();
            assertTrue(HealthProbe.healthy(base + "/up")); assertFalse(HealthProbe.healthy(base + "/down"));
            assertFalse(HealthProbe.healthy(base + "/redirect")); assertFalse(HealthProbe.healthy("file:/unused"));
        } finally { server.stop(0); }
    }
}
