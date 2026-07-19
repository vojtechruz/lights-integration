package com.vojtechruzicka.lights.hue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509TrustManager;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.security.SecureRandom;
import java.security.cert.X509Certificate;
import java.time.Duration;
import java.util.Map;

/**
 * Low-level HTTP against a single Hue bridge: v1 link-button pairing plus CLIP v2
 * GET/PUT resource calls.
 *
 * <p>The bridge serves HTTPS with a self-signed certificate whose CN is the bridge id
 * (not its IP), so both the certificate chain and hostname verification would fail.
 * We trust the connection explicitly. This is acceptable on a LAN; cert pinning against
 * the bridge id is on the roadmap for hardening (see docs/feature-analysis.md).
 */
public final class HueClient {

    // NOTE: hostname verification is disabled via the
    // jdk.internal.httpclient.disableHostnameVerification system property, which must be
    // set before the first HttpClient is created — see LightsCli.main() and build.gradle.
    // Here we only need to trust the bridge's self-signed certificate chain.

    private final String ip;
    private final ObjectMapper mapper = new ObjectMapper();
    private final HttpClient http;

    public HueClient(String ip) {
        this.ip = ip;
        this.http = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(5))
                .sslContext(trustAllContext())
                .build();
    }

    public record Credentials(String username, String clientKey) {}

    /**
     * Attempts v1 user creation. Returns {@code null} if the link button hasn't been
     * pressed yet (bridge error 101); the caller retries.
     */
    public Credentials createUser(String appName, String deviceName) throws Exception {
        String body = mapper.writeValueAsString(Map.of(
                "devicetype", appName + "#" + deviceName,
                "generateclientkey", true));
        HttpRequest req = HttpRequest.newBuilder(URI.create("https://" + ip + "/api"))
                .header("Content-Type", "application/json")
                .timeout(Duration.ofSeconds(10))
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build();
        HttpResponse<String> res = http.send(req, HttpResponse.BodyHandlers.ofString());
        JsonNode arr = mapper.readTree(res.body());
        JsonNode first = arr.isArray() && !arr.isEmpty() ? arr.get(0) : null;
        if (first == null) {
            throw new HueException("Unexpected pairing response: " + res.body());
        }
        if (first.has("error")) {
            int type = first.path("error").path("type").asInt();
            if (type == 101) {
                return null; // link button not pressed yet
            }
            throw new HueException("Pairing error: " + first.path("error").path("description").asText());
        }
        JsonNode success = first.path("success");
        return new Credentials(success.path("username").asText(), success.path("clientkey").asText(null));
    }

    public JsonNode getV2(String resourcePath, String appKey) throws Exception {
        HttpRequest req = HttpRequest.newBuilder(URI.create("https://" + ip + "/clip/v2/resource/" + resourcePath))
                .header("hue-application-key", appKey)
                .timeout(Duration.ofSeconds(10))
                .GET()
                .build();
        HttpResponse<String> res = http.send(req, HttpResponse.BodyHandlers.ofString());
        if (res.statusCode() / 100 != 2) {
            throw new HueException("GET " + resourcePath + " -> HTTP " + res.statusCode() + ": " + res.body());
        }
        return mapper.readTree(res.body());
    }

    public void putV2(String resourcePath, String appKey, Object body) throws Exception {
        String json = mapper.writeValueAsString(body);
        HttpRequest req = HttpRequest.newBuilder(URI.create("https://" + ip + "/clip/v2/resource/" + resourcePath))
                .header("hue-application-key", appKey)
                .header("Content-Type", "application/json")
                .timeout(Duration.ofSeconds(10))
                .method("PUT", HttpRequest.BodyPublishers.ofString(json))
                .build();
        HttpResponse<String> res = http.send(req, HttpResponse.BodyHandlers.ofString());
        if (res.statusCode() / 100 != 2) {
            throw new HueException("PUT " + resourcePath + " -> HTTP " + res.statusCode() + ": " + res.body());
        }
    }

    private static SSLContext trustAllContext() {
        try {
            SSLContext ctx = SSLContext.getInstance("TLS");
            ctx.init(null, new TrustManager[]{new X509TrustManager() {
                @Override
                public void checkClientTrusted(X509Certificate[] chain, String authType) {}

                @Override
                public void checkServerTrusted(X509Certificate[] chain, String authType) {}

                @Override
                public X509Certificate[] getAcceptedIssuers() {
                    return new X509Certificate[0];
                }
            }}, new SecureRandom());
            return ctx;
        } catch (Exception e) {
            throw new HueException("Cannot build TLS context: " + e.getMessage());
        }
    }
}
