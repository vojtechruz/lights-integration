package com.vojtechruzicka.lights.hue;

import com.fasterxml.jackson.databind.JsonNode;
import com.vojtechruzicka.lights.light.Effect;
import com.vojtechruzicka.lights.light.LightProvider;
import com.vojtechruzicka.lights.light.LightState;
import com.vojtechruzicka.lights.light.LightTarget;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * {@link LightProvider} backed by a Hue bridge (CLIP v2). Target ids are
 * {@code "light/<rid>"} for individual lights or {@code "grouped_light/<rid>"} for a
 * room/zone group.
 */
public final class HueProvider implements LightProvider {

    private final HueClient client;
    private final String appKey;

    public HueProvider(HueClient client, String appKey) {
        this.client = client;
        this.appKey = appKey;
    }

    @Override
    public String id() {
        return "hue";
    }

    @Override
    public List<LightTarget> listTargets() throws Exception {
        List<LightTarget> targets = new ArrayList<>();

        // Individual lights (the two-part monitor light shows up as separate lights here).
        for (JsonNode light : client.getV2("light", appKey).path("data")) {
            String rid = light.path("id").asText();
            String name = light.path("metadata").path("name").asText("(light)");
            targets.add(new LightTarget("light/" + rid, name));
        }

        // Rooms and zones expose a grouped_light service to control all their members.
        for (String type : List.of("room", "zone")) {
            for (JsonNode group : client.getV2(type, appKey).path("data")) {
                String name = group.path("metadata").path("name").asText(type);
                for (JsonNode service : group.path("services")) {
                    if ("grouped_light".equals(service.path("rtype").asText())) {
                        targets.add(new LightTarget(
                                "grouped_light/" + service.path("rid").asText(),
                                name + " (" + type + ")"));
                    }
                }
            }
        }
        return targets;
    }

    @Override
    public void apply(LightTarget target, LightState state) throws Exception {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("on", Map.of("on", state.on()));
        if (state.on()) {
            body.put("dimming", Map.of("brightness", state.brightnessPercent()));
            double[] xy = HueColor.toXy(state.color());
            body.put("color", Map.of("xy", Map.of("x", round(xy[0]), "y", round(xy[1]))));

            long ms = state.transition().toMillis();
            boolean group = target.id().startsWith("grouped_light/");
            if (ms > 0 && !group) {
                body.put("dynamics", Map.of("duration", (int) Math.min(ms, 60_000)));
            }
        }
        client.putV2(target.id(), appKey, body);
        if (state.on() && state.effect() == Effect.PULSE) {
            // One "breathe" cycle in the current colour; the engine repeats it periodically.
            client.putV2(target.id(), appKey, Map.of("alert", Map.of("action", "breathe")));
        }
    }

    private static double round(double v) {
        return Math.round(v * 10_000.0) / 10_000.0;
    }
}
