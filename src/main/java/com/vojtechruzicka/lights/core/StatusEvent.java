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
        Instant timestamp) {

    public static final int CURRENT_SCHEMA_VERSION = 1;
}
