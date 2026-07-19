package com.vojtechruzicka.lights.hue;

/** Raised when the Hue bridge returns an error or an unexpected response. */
public class HueException extends RuntimeException {
    public HueException(String message) {
        super(message);
    }
}
