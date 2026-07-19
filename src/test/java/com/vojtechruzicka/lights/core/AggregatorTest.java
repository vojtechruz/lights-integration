package com.vojtechruzicka.lights.core;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class AggregatorTest {

    private final Aggregator aggregator = new Aggregator();

    @Test
    void errorBeatsWaitingAndRunning() {
        assertEquals(AgentState.ERROR,
                aggregator.worst(List.of(AgentState.WAITING_FOR_INPUT, AgentState.ERROR, AgentState.RUNNING)));
    }

    @Test
    void waitingBeatsRunningAndSuccess() {
        assertEquals(AgentState.WAITING_FOR_INPUT,
                aggregator.worst(List.of(AgentState.RUNNING, AgentState.WAITING_FOR_INPUT, AgentState.SUCCESS)));
    }

    @Test
    void emptyIsIdle() {
        assertEquals(AgentState.IDLE, aggregator.worst(List.of()));
    }
}
