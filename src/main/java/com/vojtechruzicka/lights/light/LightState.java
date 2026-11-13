package com.vojtechruzicka.lights.light;

import java.time.Duration;

/**
 * Vendor-neutral desired state of a light: on/off, brightness (0–100), colour, an
 * optional effect, and a fade duration. A {@code LightProvider} translates this into
 * concrete vendor commands (e.g. CIE xy + dimming for Hue).
 */
public record LightState(
        boolean on,
        int brightnessPercent,
        RgbColor color,
        Effect effect,
        Duration transition) {

    public LightState {
        brightnessPercent = Math.max(0, Math.min(100, brightnessPercent));
        if (color == null) color = new RgbColor(0, 0, 0);
        if (effect == null) effect = Effect.NONE;
        if (transition == null) transition = Duration.ZERO;
    }

    public static LightState solid(RgbColor color, int brightnessPercent) {
        return new LightState(true, brightnessPercent, color, Effect.NONE, Duration.ofMillis(400));
    }

    public LightState withEffect(Effect newEffect) {
        return new LightState(on, brightnessPercent, color, newEffect, transition);
    }

    public static LightState off() {
        return new LightState(false, 0, new RgbColor(0, 0, 0), Effect.NONE, Duration.ofMillis(400));
    }
}
