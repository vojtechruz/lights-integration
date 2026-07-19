package com.vojtechruzicka.lights.light;

import java.util.List;

/**
 * Output seam: turns vendor-neutral {@link LightState} into concrete light commands.
 * First implementation is {@code HueProvider}; other vendors (HDMI sync box, LIFX,
 * Home Assistant…) plug in here without touching the core.
 */
public interface LightProvider extends AutoCloseable {

    String id();

    List<LightTarget> listTargets() throws Exception;

    void apply(LightTarget target, LightState state) throws Exception;

    @Override
    default void close() throws Exception {}
}
