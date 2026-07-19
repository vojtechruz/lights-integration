package com.vojtechruzicka.lights.light;

/** A plain sRGB colour (0–255 per channel), independent of any light vendor. */
public record RgbColor(int red, int green, int blue) {

    public RgbColor {
        red = clamp(red);
        green = clamp(green);
        blue = clamp(blue);
    }

    private static int clamp(int v) {
        return Math.max(0, Math.min(255, v));
    }
}
