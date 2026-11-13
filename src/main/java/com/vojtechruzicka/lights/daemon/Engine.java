package com.vojtechruzicka.lights.daemon;

import com.vojtechruzicka.lights.core.AgentState;
import com.vojtechruzicka.lights.core.Aggregator;
import com.vojtechruzicka.lights.core.StateChangeListener;
import com.vojtechruzicka.lights.core.StatusEvent;
import com.vojtechruzicka.lights.core.Zone;
import com.vojtechruzicka.lights.light.LightState;
import com.vojtechruzicka.lights.light.RgbColor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * The heart of the daemon. Tracks the current state of every agent, derives the
 * time-based state the hooks can't send ({@code LONG_RUNNING}), and pushes a "frame" to
 * the zone's light — only when it actually changes (debounce).
 *
 * <p>A frame is either one colour (the worst state across agents) or, with
 * {@code maxSegments > 1} and 2+ active agents, one section per agent in a stable order
 * (by when the agent was first seen). With more agents than sections, the last section
 * shows the worst state of the remaining ones.
 *
 * <p>{@code SUCCESS} is held, and when no agent is active the light keeps its last frame
 * instead of switching off. An IDLE report (SessionEnd incl. /clear, SessionStart) removes the
 * agent; agents with no event for {@link #STALE_AFTER} are dropped too (sessions that
 * vanished without a SessionEnd, e.g. a killed terminal).
 */
public final class Engine {

    private static final Logger log = LoggerFactory.getLogger(Engine.class);

    /**
     * Pulse = the engine itself alternating brightness (no Hue "breathe" alert, which keeps
     * running on the bulb after a colour change). Each half-cycle fades for slightly less
     * than its length. Pulsing stops on its own after {@code PULSE_DURATION}.
     */
    static final Duration PULSE_DURATION = Duration.ofMinutes(2);
    private static final Duration PULSE_HALF = Duration.ofSeconds(2);
    private static final Duration PULSE_FADE = Duration.ofMillis(1800);
    private static final int PULSE_LOW_PERCENT = 15;

    static final Duration STALE_AFTER = Duration.ofMinutes(60);

    private final Zone zone;
    private final Aggregator aggregator;
    private final Duration longRunningThreshold;
    private int maxSegments = 1;

    private final Map<String, AgentRecord> agents = new HashMap<>();
    private final ScheduledExecutorService ticker = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "engine-ticker");
        t.setDaemon(true);
        return t;
    });

    private StateChangeListener listener = StateChangeListener.NONE;
    private List<AgentState> lastFrame;
    private boolean lastPulse;
    private boolean pulseLow;
    private Instant lastPulseAt = Instant.EPOCH;
    private long sequence;

    /** Per-agent base state plus when it was entered (for time-based derivations). */
    private static final class AgentRecord {
        final long order;
        AgentState base;
        Instant since;
        Instant lastSeen;
        Instant remindedAt; // null = no active reminder

        AgentRecord(long order, AgentState base, Instant since) {
            this.order = order;
            this.base = base;
            this.since = since;
            this.lastSeen = since;
        }
    }

    public Engine(Zone zone, Aggregator aggregator, Duration longRunningThreshold) {
        this.zone = zone;
        this.aggregator = aggregator;
        this.longRunningThreshold = longRunningThreshold;
    }

    /** Per-agent state changes (e.g. for sounds). Set before {@link #start()}. */
    public void setStateChangeListener(StateChangeListener listener) {
        this.listener = listener != null ? listener : StateChangeListener.NONE;
    }

    /** 1 = one colour (worst state wins); 2–5 = one light section per agent. */
    public void setMaxSegments(int maxSegments) {
        this.maxSegments = Math.max(1, Math.min(5, maxSegments));
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
                record.remindedAt = now;
                record.lastSeen = now;
                recomputeAndApply();
            }
            return;
        }
        AgentState incoming = event.state();
        if (incoming == AgentState.IDLE) {
            // Session ended (exit, /clear, logout) or just started: it holds no section.
            if (agents.remove(event.agentId()) != null) {
                log.info("session {} ended", event.agentId());
            }
            recomputeAndApply();
            return;
        }
        AgentState previous = record != null ? record.base : null;

        if (record == null) {
            agents.put(event.agentId(), new AgentRecord(sequence++, incoming, now));
        } else {
            record.lastSeen = now;
            record.remindedAt = null;
            // Repeated RUNNING keeps the original start so tool-use events don't keep
            // resetting the LONG_RUNNING timer within one continuous working stretch.
            if (!(incoming == AgentState.RUNNING && record.base == AgentState.RUNNING)) {
                record.base = incoming;
                record.since = now;
            }
        }
        if (incoming != previous) {
            try {
                listener.onStateChange(event.agentId(), previous, incoming);
            } catch (Exception e) {
                log.warn("state change listener failed: {}", e.getMessage());
            }
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

    private boolean pulsing(AgentRecord r, Instant now) {
        return r.remindedAt != null && Duration.between(r.remindedAt, now).compareTo(PULSE_DURATION) < 0;
    }

    private void recomputeAndApply() {
        // Elapsed time (LONG_RUNNING, pulse, staleness) is measured against the real clock;
        // each record's timestamps were stamped from its events.
        Instant now = Instant.now();
        agents.values().removeIf(r -> Duration.between(r.lastSeen, now).compareTo(STALE_AFTER) > 0);

        List<AgentRecord> active = new ArrayList<>();
        for (AgentRecord r : agents.values()) {
            if (r.base != AgentState.IDLE) {
                active.add(r);
            }
        }
        active.sort(Comparator.comparingLong(r -> r.order));

        if (active.isEmpty()) {
            if (lastFrame != null) {
                return; // hold the last frame: idle must not switch the light off
            }
            apply(List.of(AgentState.IDLE), false, true, now);
            return;
        }

        List<AgentState> states = new ArrayList<>(active.size());
        for (AgentRecord r : active) {
            states.add(effectiveState(r, now));
        }
        AgentState worst = aggregator.worst(states);

        List<AgentState> frame;
        boolean pulse = false;
        if (maxSegments <= 1 || active.size() <= 1) {
            frame = List.of(worst);
            for (AgentRecord r : active) {
                if (pulsing(r, now) && effectiveState(r, now) == worst) {
                    pulse = true;
                }
            }
        } else {
            frame = new ArrayList<>();
            if (states.size() <= maxSegments) {
                frame.addAll(states);
            } else {
                frame.addAll(states.subList(0, maxSegments - 1));
                frame.add(aggregator.worst(states.subList(maxSegments - 1, states.size())));
            }
            for (AgentRecord r : active) {
                pulse |= pulsing(r, now); // whole strip pulses so a reminder is not missed
            }
        }

        boolean changed = !frame.equals(lastFrame) || pulse != lastPulse;
        boolean pulseDue = pulse && Duration.between(lastPulseAt, now).compareTo(PULSE_HALF) >= 0;
        if (changed || pulseDue) {
            apply(frame, pulse, changed, now);
        }
    }

    private void apply(List<AgentState> frame, boolean pulse, boolean changed, Instant now) {
        LightState desired = lightFor(frame);
        boolean nextLow = false;
        if (pulse && desired.on()) {
            // First pulse step dims, then alternate; a plain (non-pulse) apply restores full.
            nextLow = changed || !pulseLow;
            int brightness = nextLow
                    ? Math.min(PULSE_LOW_PERCENT, desired.brightnessPercent())
                    : desired.brightnessPercent();
            desired = desired.withBrightness(brightness, PULSE_FADE);
        }
        try {
            zone.provider().apply(zone.target(), desired);
            if (changed) {
                log.info("{} -> {}{}", frame.size() > 1 ? "segments" : "aggregate state",
                        frame.size() > 1 ? frame : frame.getFirst(), pulse ? " (pulse)" : "");
            }
            lastFrame = frame;
            lastPulse = pulse;
            pulseLow = nextLow;
            if (pulse) {
                lastPulseAt = now;
            }
        } catch (Exception e) {
            // Keep lastFrame unchanged so the next tick retries (bridge/network hiccup).
            log.warn("failed to apply {}: {}", frame, e.getMessage());
        }
    }

    /** One state → its mapped light; several → one section per state (brightest wins). */
    private LightState lightFor(List<AgentState> frame) {
        if (frame.size() == 1) {
            return zone.mapping().lightFor(frame.getFirst());
        }
        LightState worst = zone.mapping().lightFor(aggregator.worst(frame));
        List<RgbColor> colors = new ArrayList<>(frame.size());
        int brightness = 0;
        for (AgentState s : frame) {
            LightState ls = zone.mapping().lightFor(s);
            colors.add(ls.on() ? ls.color() : new RgbColor(0, 0, 0));
            brightness = Math.max(brightness, ls.brightnessPercent());
        }
        return new LightState(true, brightness, worst.color(), worst.effect(), worst.transition(), colors);
    }
}
