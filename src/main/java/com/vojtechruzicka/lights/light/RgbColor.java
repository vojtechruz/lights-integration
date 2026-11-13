package com.vojtechruzicka.lights.light;

/** A plain sRGB colour (0–255 per channel), independent of any light vendor. */
public record RgbColor(int red, int green, int blue) {

    public RgbColor {
        red = clamp(red);
        green = clamp(green);
        blue = clamp(blue);
    }

    /** Parses {@code #RRGGBB} (the {@code #} is optional). */
    public static RgbColor parse(String hex) {
        String h = hex.strip();
        if (h.startsWith("#")) {
            h = h.substring(1);
        }
        if (!h.matches("[0-9a-fA-F]{6}")) {
            throw new IllegalArgumentException("expected #RRGGBB, got '" + hex + "'");
        }
        int v = Integer.parseInt(h, 16);
        return new RgbColor((v >> 16) & 0xFF, (v >> 8) & 0xFF, v & 0xFF);
    }

    public String hex() {
        return String.format("#%02X%02X%02X", red, green, blue);
    }

    private static int clamp(int v) {
        return Math.max(0, Math.min(255, v));
    }
}
