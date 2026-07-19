package com.vojtechruzicka.lights.config;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * Persisted runtime configuration. Lives under {@code config/config.json} (gitignored)
 * because it holds the Hue key. Written by {@code pair}, read by {@code test} / {@code run}.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record AppConfig(HueConfig hue, DaemonConfig daemon, String monitorTargetId) {

    public record HueConfig(String bridgeIp, String appKey, String clientKey) {}

    /** {@code longRunningSeconds} is the configurable LONG_RUNNING threshold (default 5 min). */
    public record DaemonConfig(int ingestPort, int longRunningSeconds) {}

    public static AppConfig defaults() {
        return new AppConfig(null, new DaemonConfig(47600, 300), null);
    }

    public AppConfig withHue(HueConfig hue) {
        return new AppConfig(hue, daemon, monitorTargetId);
    }

    public AppConfig withMonitorTargetId(String id) {
        return new AppConfig(hue, daemon, id);
    }
}
