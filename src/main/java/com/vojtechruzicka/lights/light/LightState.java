package com.vojtechruzicka.lights.light;

import java.time.Duration;
import java.util.List;

/**
 * Vendor-neutral desired state of a light: on/off, brightness (0–100), colour, an
 * optional effect, and a fade duration. A {@code LightProvider} translates this into
 * concrete vendor commands (e.g. CIE xy + dimming for Hue).
 *
 * <p>{@code segments}: when it has 2+ colours, a light that supports it (Hue gradient
 * strip) shows them as separate sections in this order; {@code color} is then the
 * fallback for lights without gradient support. Empty = one solid colour.
 */
public record LightState(
        boolean on,
        int brightnessPercent,
        RgbColor color,
        Effect effect,
        Duration transition,
        List<RgbColor> segments) {

    public LightState {
        brightnessPercent = Math.max(0, Math.min(100, brightnessPercent));
        if (color == null) color = new RgbColor(0, 0, 0);
        if (effect == null) effect = Effect.NONE;
        if (transition == null) transition = Duration.ZERO;
        segments = segments == null ? List.of() : List.copyOf(segments);
    }

    public LightState(boolean on, int brightnessPercent, RgbColor color, Effect effect, Duration transition) {
        this(on, brightnessPercent, color, effect, transition, List.of());
    }

    public static LightState solid(RgbColor color, int brightnessPercent) {
        return new LightState(true, brightnessPercent, color, Effect.NONE, Duration.ofMillis(400));
    }

    public boolean isSegmented() {
        return segments.size() >= 2;
    }

    public LightState withEffect(Effect newEffect) {
        return new LightState(on, brightnessPercent, color, newEffect, transition, segments);
    }

    public LightState withBrightness(int percent, Duration fade) {
        return new LightState(on, percent, color, effect, fade, segments);
    }

    public LightState withSegments(List<RgbColor> newSegments) {
        return new LightState(on, brightnessPercent, color, effect, transition, newSegments);
    }

    public static LightState off() {
        return new LightState(false, 0, new RgbColor(0, 0, 0), Effect.NONE, Duration.ofMillis(400));
    }
}
