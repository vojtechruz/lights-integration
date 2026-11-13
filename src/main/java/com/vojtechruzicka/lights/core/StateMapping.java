package com.vojtechruzicka.lights.core;

import com.vojtechruzicka.lights.light.LightState;
import com.vojtechruzicka.lights.light.RgbColor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.EnumMap;
import java.util.Map;

/**
 * Maps each {@link AgentState} to a {@link LightState}. Kept as data (not a hardcoded
 * switch) so mappings are config-driven: {@link #defaults()} plus per-state overrides
 * from the {@code colors} section of the config.
 */
public final class StateMapping {

    private static final Logger log = LoggerFactory.getLogger(StateMapping.class);

    /** One state's colour in config: {@code "#RRGGBB"} or {@code "off"}, brightness 0–100. */
    public record ColorSpec(String color, Integer brightness) {}

    private final Map<AgentState, LightState> map;

    public StateMapping(Map<AgentState, LightState> map) {
        this.map = new EnumMap<>(map);
    }

    public LightState lightFor(AgentState state) {
        return map.getOrDefault(state, LightState.off());
    }

    /** Built-in colours, used for every state the config does not override. */
    public static StateMapping defaults() {
        Map<AgentState, LightState> m = new EnumMap<>(AgentState.class);
        m.put(AgentState.ERROR, LightState.solid(new RgbColor(255, 0, 0), 80));
        m.put(AgentState.WAITING_FOR_INPUT, LightState.solid(new RgbColor(255, 140, 0), 80));
        m.put(AgentState.LONG_RUNNING, LightState.solid(new RgbColor(160, 0, 255), 80));
        m.put(AgentState.RUNNING, LightState.solid(new RgbColor(0, 60, 255), 70));
        m.put(AgentState.SUCCESS, LightState.solid(new RgbColor(0, 255, 60), 90));
        m.put(AgentState.IDLE, LightState.off());
        return new StateMapping(m);
    }

    /**
     * Defaults overridden by config ({@code state name -> ColorSpec}). Invalid entries are
     * logged and skipped, so a typo never stops the daemon.
     */
    public static StateMapping fromConfig(Map<String, ColorSpec> config) {
        StateMapping base = defaults();
        Map<AgentState, LightState> m = new EnumMap<>(base.map);
        if (config == null) {
            return base;
        }
        config.forEach((stateName, spec) -> {
            try {
                AgentState state = AgentState.valueOf(stateName);
                m.put(state, toLight(spec, base.lightFor(state)));
            } catch (IllegalArgumentException e) {
                log.warn("ignoring colors.{}: {}", stateName, e.getMessage());
            }
        });
        return new StateMapping(m);
    }

    private static LightState toLight(ColorSpec spec, LightState fallback) {
        if (spec == null || spec.color() == null) {
            int b = spec != null && spec.brightness() != null ? spec.brightness() : fallback.brightnessPercent();
            return fallback.on() ? LightState.solid(fallback.color(), b) : fallback;
        }
        if ("off".equalsIgnoreCase(spec.color().strip())) {
            return LightState.off();
        }
        int brightness = spec.brightness() != null ? spec.brightness()
                : (fallback.on() ? fallback.brightnessPercent() : 80);
        return LightState.solid(RgbColor.parse(spec.color()), brightness);
    }

    /** Human-readable summary for the startup banner. */
    public String describe() {
        StringBuilder sb = new StringBuilder();
        for (AgentState s : AgentState.values()) {
            LightState l = lightFor(s);
            if (!sb.isEmpty()) {
                sb.append(", ");
            }
            sb.append(s).append('=').append(l.on() ? l.color().hex() + "@" + l.brightnessPercent() + "%" : "off");
        }
        return sb.toString();
    }
}
