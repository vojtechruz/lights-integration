package com.vojtechruzicka.lights.config;

import com.fasterxml.jackson.annotation.JsonInclude;

import com.vojtechruzicka.lights.core.StateMapping;

import java.util.Map;

/**
 * Persisted runtime configuration. Lives under {@code config/config.json} (gitignored)
 * because it holds the Hue key. Written by {@code pair}, read by {@code test} / {@code run}.
 *
 * <p>{@code sounds} is optional: agent state name ({@code SUCCESS}, {@code WAITING_FOR_INPUT},
 * {@code ERROR}, …) → path to a WAV file played when a session enters that state. Omit it or
 * leave a value empty for no sound.
 *
 * <p>{@code maxSegments} is optional: 2–5 = with several active sessions, each gets its own
 * section of a gradient strip; absent or 1 = one colour (worst state wins).
 *
 * <p>{@code colors} is optional: state name → {@code {"color": "#RRGGBB" | "off", "brightness": 0-100}};
 * states not listed keep the built-in colours ({@link StateMapping#defaults()}).
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record AppConfig(HueConfig hue, DaemonConfig daemon, String monitorTargetId, Map<String, String> sounds,
                        Integer maxSegments, Map<String, StateMapping.ColorSpec> colors) {

    public record HueConfig(String bridgeIp, String appKey, String clientKey) {}

    /** {@code longRunningSeconds} is the configurable LONG_RUNNING threshold (default 5 min). */
    public record DaemonConfig(int ingestPort, int longRunningSeconds) {}

    public static AppConfig defaults() {
        return new AppConfig(null, new DaemonConfig(47600, 300), null, null, null, null);
    }

    public AppConfig withHue(HueConfig hue) {
        return new AppConfig(hue, daemon, monitorTargetId, sounds, maxSegments, colors);
    }

    public AppConfig withMonitorTargetId(String id) {
        return new AppConfig(hue, daemon, id, sounds, maxSegments, colors);
    }
}
