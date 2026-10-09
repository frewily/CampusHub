package io.github.frewily.campushub.tools;

import java.net.*;

/** JRE-only container probe, launched through Spring Boot's PropertiesLauncher without starting Spring. */
public final class HealthProbe {
    private HealthProbe() { }
    public static void main(String[] args) { System.exit(args.length == 1 && healthy(args[0]) ? 0 : 1); }
    public static boolean healthy(String address) {
        HttpURLConnection connection = null;
        try {
            URL url = new URL(address);
            if (!"http".equals(url.getProtocol()) && !"https".equals(url.getProtocol())) return false;
            connection = (HttpURLConnection) url.openConnection();
            connection.setConnectTimeout(1000); connection.setReadTimeout(8000);
            connection.setInstanceFollowRedirects(false);
            return connection.getResponseCode() == 200;
        } catch (Exception unavailable) { return false; }
        finally { if (connection != null) connection.disconnect(); }
    }
}
