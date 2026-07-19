package com.vojtechruzicka.lights.hue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

/**
 * Finds Hue bridges on the local network via Philips' cloud discovery endpoint
 * ({@code https://discovery.meethue.com}). This is a normal, publicly-trusted HTTPS call,
 * so it uses a default client (unlike bridge control, which is self-signed).
 * mDNS discovery can be added later as an offline fallback.
 */
public final class HueDiscovery {

    private static final String CLOUD_DISCOVERY = "https://discovery.meethue.com";

    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(5))
            .build();
    private final ObjectMapper mapper = new ObjectMapper();

    public record Bridge(String id, String ip) {}

    public List<Bridge> discover() throws Exception {
        HttpRequest req = HttpRequest.newBuilder(URI.create(CLOUD_DISCOVERY))
                .timeout(Duration.ofSeconds(10))
                .GET()
                .build();
        HttpResponse<String> res = http.send(req, HttpResponse.BodyHandlers.ofString());
        if (res.statusCode() / 100 != 2) {
            throw new HueException("Cloud discovery failed: HTTP " + res.statusCode());
        }
        List<Bridge> bridges = new ArrayList<>();
        for (JsonNode node : mapper.readTree(res.body())) {
            bridges.add(new Bridge(
                    node.path("id").asText(null),
                    node.path("internalipaddress").asText(null)));
        }
        return bridges;
    }
}
