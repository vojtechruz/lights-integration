package com.vojtechruzicka.lights.core;

/**
 * Notified when a single agent's reported state changes (per agent, not the aggregate),
 * e.g. to play a sound when one session finishes while another is still working.
 */
@FunctionalInterface
public interface StateChangeListener {

    /** {@code from} is {@code null} for a newly seen agent. */
    void onStateChange(String agentId, AgentState from, AgentState to);

    StateChangeListener NONE = (agentId, from, to) -> {};
}
