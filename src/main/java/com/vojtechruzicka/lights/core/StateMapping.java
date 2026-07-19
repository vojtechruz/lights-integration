package com.vojtechruzicka.lights.core;

import com.vojtechruzicka.lights.light.LightState;

import java.util.EnumMap;
import java.util.Map;

/**
 * Maps each {@link AgentState} to a {@link LightState}. Kept as data (not a hardcoded
 * switch) so mappings can be config-driven and, later, user-customisable.
 */
public final class StateMapping {

    private final Map<AgentState, LightState> map;

    public StateMapping(Map<AgentState, LightState> map) {
        this.map = new EnumMap<>(map);
    }

    public LightState lightFor(AgentState state) {
        return map.getOrDefault(state, LightState.off());
    }
}
