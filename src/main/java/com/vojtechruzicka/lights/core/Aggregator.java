package com.vojtechruzicka.lights.core;

import java.util.Collection;
import java.util.Comparator;

/** Picks the worst (most severe) state among several agents. */
public final class Aggregator {

    /** Returns the most severe state, or {@link AgentState#IDLE} if none are present. */
    public AgentState worst(Collection<AgentState> states) {
        return states.stream()
                .max(Comparator.comparingInt(AgentState::severity))
                .orElse(AgentState.IDLE);
    }
}
