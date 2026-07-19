package com.vojtechruzicka.lights.light;

/**
 * A controllable light or group. {@code id} is provider-specific and opaque to the core
 * (for Hue it is {@code "light/<rid>"} or {@code "grouped_light/<rid>"}); {@code name}
 * is human-readable.
 */
public record LightTarget(String id, String name) {}
