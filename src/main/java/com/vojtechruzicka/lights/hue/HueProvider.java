package com.vojtechruzicka.lights.hue;

import com.fasterxml.jackson.databind.JsonNode;
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
            boolean group = target.id().startsWith("grouped_light/");
            if (state.isSegmented() && !group) {
                // Gradient strip: separate sections in order (max 5 points on Hue).
                List<Map<String, Object>> points = new ArrayList<>();
                for (var c : state.segments().subList(0, Math.min(5, state.segments().size()))) {
                    double[] p = HueColor.toXy(c);
                    points.add(Map.of("color", Map.of("xy", Map.of("x", round(p[0]), "y", round(p[1])))));
                }
                body.put("gradient", Map.of("mode", "segmented_palette", "points", points));
            } else {
                // A plain colour also clears any previous gradient on the strip.
                double[] xy = HueColor.toXy(state.color());
                body.put("color", Map.of("xy", Map.of("x", round(xy[0]), "y", round(xy[1]))));
            }

            long ms = state.transition().toMillis();
            if (ms > 0 && !group) {
                body.put("dynamics", Map.of("duration", (int) Math.min(ms, 60_000)));
            }
        }
        client.putV2(target.id(), appKey, body);
    }

    private static double round(double v) {
        return Math.round(v * 10_000.0) / 10_000.0;
    }
}
