package com.vojtechruzicka.lights.core;

import java.time.Instant;

/**
 * A single state report from a {@link StatusSource}. {@code schemaVersion} is baked in
 * from day one so the ingest contract can evolve without breaking external producers
 * (hooks, a future Pomodoro integration, other tools).
 */
public record StatusEvent(
        int schemaVersion,
        String sourceId,
        String agentId,
        AgentState state,
        Instant timestamp,
        boolean reminder) {

    public static final int CURRENT_SCHEMA_VERSION = 1;

    /** A plain state report. */
    public StatusEvent(int schemaVersion, String sourceId, String agentId, AgentState state, Instant timestamp) {
        this(schemaVersion, sourceId, agentId, state, timestamp, false);
    }

    /**
     * A "nobody reacted for a while" reminder for an agent. Carries no state: the agent
     * keeps its current state and the light starts pulsing in that state's colour.
     */
    public static StatusEvent reminder(String sourceId, String agentId, Instant timestamp) {
        return new StatusEvent(CURRENT_SCHEMA_VERSION, sourceId, agentId, null, timestamp, true);
    }
}
