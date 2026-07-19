package com.vojtechruzicka.lights.core;

import com.vojtechruzicka.lights.light.LightProvider;
import com.vojtechruzicka.lights.light.LightTarget;

/**
 * Binds a set of sources (via the aggregator) to one light target and a state→colour
 * mapping. MVP has a single zone (agents → monitor light); later zones (e.g. Pomodoro)
 * reuse the same machinery.
 */
public record Zone(String name, LightProvider provider, LightTarget target, StateMapping mapping) {}
