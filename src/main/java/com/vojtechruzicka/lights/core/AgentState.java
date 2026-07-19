package com.vojtechruzicka.lights.core;

/**
 * State an agent can be in. The ordinal severity drives aggregation across multiple
 * agents: the light shows the <em>worst</em> state any one agent is in.
 *
 * <p>Severity order (high → low): {@code ERROR > WAITING_FOR_INPUT > LONG_RUNNING >
 * RUNNING > SUCCESS > IDLE}.
 */
public enum AgentState {

    IDLE(0),
    SUCCESS(1),
    RUNNING(2),
    LONG_RUNNING(3),
    WAITING_FOR_INPUT(4),
    ERROR(5);

    private final int severity;

    AgentState(int severity) {
        this.severity = severity;
    }

    public int severity() {
        return severity;
    }

    public boolean isMoreSevereThan(AgentState other) {
        return severity > other.severity;
    }
}
