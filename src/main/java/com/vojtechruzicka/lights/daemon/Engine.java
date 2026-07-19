package com.vojtechruzicka.lights.daemon;

import com.vojtechruzicka.lights.core.AgentState;
import com.vojtechruzicka.lights.core.Aggregator;
import com.vojtechruzicka.lights.core.StatusEvent;
import com.vojtechruzicka.lights.core.Zone;
import com.vojtechruzicka.lights.light.LightState;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * The heart of the daemon. Tracks the current state of every agent, derives the two
 * time-based states the hooks can't send ({@code LONG_RUNNING}, and the {@code SUCCESS}
 * flash decaying to {@code IDLE}), aggregates worst-wins across agents, and pushes the
 * resulting colour to the zone's light — only when it actually changes (debounce).
 */
public final class Engine {

    private static final Logger log = LoggerFactory.getLogger(Engine.class);
    private static final Duration SUCCESS_FLASH = Duration.ofSeconds(3);

    private final Zone zone;
    private final Aggregator aggregator;
    private final Duration longRunningThreshold;

    private final Map<String, AgentRecord> agents = new HashMap<>();
    private final ScheduledExecutorService ticker = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "engine-ticker");
        t.setDaemon(true);
        return t;
    });

    private AgentState lastApplied;

    /** Per-agent base state plus when it was entered (for time-based derivations). */
    private static final class AgentRecord {
        AgentState base;
        Instant since;

        AgentRecord(AgentState base, Instant since) {
            this.base = base;
            this.since = since;
        }
    }

    public Engine(Zone zone, Aggregator aggregator, Duration longRunningThreshold) {
        this.zone = zone;
        this.aggregator = aggregator;
        this.longRunningThreshold = longRunningThreshold;
    }

    /** Starts the 1 Hz ticker that applies time-based transitions. */
    public void start() {
        ticker.scheduleAtFixedRate(this::safeTick, 1, 1, TimeUnit.SECONDS);
    }

    public void stop() {
        ticker.shutdownNow();
    }

    public synchronized void onEvent(StatusEvent event) {
        Instant now = event.timestamp() != null ? event.timestamp() : Instant.now();
        AgentState incoming = event.state();
        AgentRecord record = agents.get(event.agentId());

        if (record == null) {
            agents.put(event.agentId(), new AgentRecord(incoming, now));
        } else if (incoming == AgentState.RUNNING && record.base == AgentState.RUNNING) {
            // Keep the original RUNNING start so repeated tool-use events don't keep
            // resetting the LONG_RUNNING timer within one continuous working stretch.
            record.base = AgentState.RUNNING;
        } else {
            record.base = incoming;
            record.since = now;
        }
        recomputeAndApply();
    }

    private void safeTick() {
        try {
            synchronized (this) {
                recomputeAndApply();
            }
        } catch (Exception e) {
            log.warn("engine tick failed: {}", e.getMessage());
        }
    }

    private AgentState effectiveState(AgentRecord r, Instant now) {
        return switch (r.base) {
            case RUNNING -> Duration.between(r.since, now).compareTo(longRunningThreshold) > 0
                    ? AgentState.LONG_RUNNING : AgentState.RUNNING;
            case SUCCESS -> Duration.between(r.since, now).compareTo(SUCCESS_FLASH) > 0
                    ? AgentState.IDLE : AgentState.SUCCESS;
            default -> r.base;
        };
    }

    private void recomputeAndApply() {
        // Elapsed time (for LONG_RUNNING / SUCCESS decay) is always measured against the
        // real current time; each record's `since` was stamped from its event.
        Instant now = Instant.now();
        List<AgentState> effective = new ArrayList<>(agents.size());
        for (AgentRecord r : agents.values()) {
            effective.add(effectiveState(r, now));
        }
        AgentState aggregate = aggregator.worst(effective);
        if (aggregate == lastApplied) {
            return;
        }
        LightState desired = zone.mapping().lightFor(aggregate);
        try {
            zone.provider().apply(zone.target(), desired);
            log.info("aggregate state -> {}", aggregate);
            lastApplied = aggregate;
        } catch (Exception e) {
            // Keep lastApplied unchanged so the next tick retries (bridge/network hiccup).
            log.warn("failed to apply {}: {}", aggregate, e.getMessage());
        }
    }
}
