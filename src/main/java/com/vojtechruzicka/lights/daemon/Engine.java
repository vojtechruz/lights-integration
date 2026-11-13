package com.vojtechruzicka.lights.daemon;

import com.vojtechruzicka.lights.core.AgentState;
import com.vojtechruzicka.lights.core.Aggregator;
import com.vojtechruzicka.lights.core.StatusEvent;
import com.vojtechruzicka.lights.core.Zone;
import com.vojtechruzicka.lights.light.Effect;
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
 * time-based state the hooks can't send ({@code LONG_RUNNING}), aggregates worst-wins
 * across agents, and pushes the resulting colour to the zone's light — only when it
 * actually changes (debounce).
 *
 * <p>{@code SUCCESS} is held (not a transient flash), and an aggregate {@code IDLE} keeps
 * the last applied colour instead of switching the light off, so the light always shows
 * the last meaningful state until something new happens.
 */
public final class Engine {

    private static final Logger log = LoggerFactory.getLogger(Engine.class);

    private final Zone zone;
    private final Aggregator aggregator;
    private final Duration longRunningThreshold;

    private final Map<String, AgentRecord> agents = new HashMap<>();
    private final ScheduledExecutorService ticker = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "engine-ticker");
        t.setDaemon(true);
        return t;
    });

    /** How often to repeat the Hue "breathe" while an agent's reminder is active. */
    private static final Duration PULSE_PERIOD = Duration.ofSeconds(4);

    private AgentState lastApplied;
    private boolean lastPulse;
    private Instant lastPulseAt = Instant.EPOCH;

    /** Per-agent base state plus when it was entered (for time-based derivations). */
    private static final class AgentRecord {
        AgentState base;
        Instant since;
        boolean reminded;

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
        AgentRecord record = agents.get(event.agentId());
        if (event.reminder()) {
            if (record != null) {
                record.reminded = true;
                recomputeAndApply();
            }
            return;
        }
        AgentState incoming = event.state();

        if (record == null) {
            agents.put(event.agentId(), new AgentRecord(incoming, now));
        } else if (incoming == AgentState.RUNNING && record.base == AgentState.RUNNING) {
            record.reminded = false;
            // Keep the original RUNNING start so repeated tool-use events don't keep
            // resetting the LONG_RUNNING timer within one continuous working stretch.
            record.base = AgentState.RUNNING;
        } else {
            record.base = incoming;
            record.since = now;
            record.reminded = false;
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
            default -> r.base;
        };
    }

    private void recomputeAndApply() {
        // Elapsed time (for LONG_RUNNING) is always measured against the
        // real current time; each record's `since` was stamped from its event.
        Instant now = Instant.now();
        List<AgentState> effective = new ArrayList<>(agents.size());
        for (AgentRecord r : agents.values()) {
            effective.add(effectiveState(r, now));
        }
        AgentState aggregate = aggregator.worst(effective);
        // Pulse when an agent in the winning state got a reminder (idle_prompt).
        boolean pulse = false;
        for (AgentRecord r : agents.values()) {
            if (r.reminded && effectiveState(r, now) == aggregate) {
                pulse = true;
                break;
            }
        }
        if (aggregate == AgentState.IDLE && lastApplied != null) {
            // Hold the last colour: idle (session start/end) must not switch the light off.
            return;
        }
        boolean changed = aggregate != lastApplied || pulse != lastPulse;
        boolean pulseDue = pulse && Duration.between(lastPulseAt, now).compareTo(PULSE_PERIOD) >= 0;
        if (!changed && !pulseDue) {
            return;
        }
        LightState desired = zone.mapping().lightFor(aggregate);
        if (pulse) {
            desired = desired.withEffect(Effect.PULSE);
        }
        try {
            zone.provider().apply(zone.target(), desired);
            if (changed) {
                log.info("aggregate state -> {}{}", aggregate, pulse ? " (pulse)" : "");
            }
            lastApplied = aggregate;
            lastPulse = pulse;
            if (pulse) {
                lastPulseAt = now;
            }
        } catch (Exception e) {
            // Keep lastApplied unchanged so the next tick retries (bridge/network hiccup).
            log.warn("failed to apply {}: {}", aggregate, e.getMessage());
        }
    }
}
