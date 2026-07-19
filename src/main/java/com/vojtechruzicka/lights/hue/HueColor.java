package com.vojtechruzicka.lights.hue;

import com.vojtechruzicka.lights.light.RgbColor;

/** Converts sRGB to the CIE xy chromaticity the Hue CLIP v2 colour API expects. */
final class HueColor {

    private HueColor() {}

    static double[] toXy(RgbColor c) {
        double r = gammaExpand(c.red() / 255.0);
        double g = gammaExpand(c.green() / 255.0);
        double b = gammaExpand(c.blue() / 255.0);

        double x = r * 0.4124 + g * 0.3576 + b * 0.1805;
        double y = r * 0.2126 + g * 0.7152 + b * 0.0722;
        double z = r * 0.0193 + g * 0.1192 + b * 0.9505;

        double sum = x + y + z;
        if (sum == 0.0) {
            return new double[]{0.0, 0.0};
        }
        return new double[]{x / sum, y / sum};
    }

    private static double gammaExpand(double v) {
        return v > 0.04045 ? Math.pow((v + 0.055) / 1.055, 2.4) : v / 12.92;
    }
}
