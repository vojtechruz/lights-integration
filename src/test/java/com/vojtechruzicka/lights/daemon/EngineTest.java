package com.vojtechruzicka.lights.daemon;

import com.vojtechruzicka.lights.core.AgentState;
import com.vojtechruzicka.lights.core.Aggregator;
import com.vojtechruzicka.lights.core.StateMapping;
import com.vojtechruzicka.lights.core.StatusEvent;
import com.vojtechruzicka.lights.core.Zone;
import com.vojtechruzicka.lights.light.Effect;
import com.vojtechruzicka.lights.light.LightProvider;
import com.vojtechruzicka.lights.light.LightState;
import com.vojtechruzicka.lights.light.LightTarget;
import com.vojtechruzicka.lights.light.RgbColor;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

class EngineTest {

    /** Captures the last applied light state instead of talking to a bridge. */
    private static final class RecordingProvider implements LightProvider {
        LightState last;

        @Override public String id() { return "recording"; }
        @Override public List<LightTarget> listTargets() { return List.of(); }
        @Override public void apply(LightTarget target, LightState state) { this.last = state; }
    }

    private static StateMapping distinctMapping() {
        Map<AgentState, LightState> m = new EnumMap<>(AgentState.class);
        int b = 10;
        for (AgentState s : AgentState.values()) {
            m.put(s, LightState.solid(new RgbColor(b, 0, 0), 50));
            b += 10;
        }
        return new StateMapping(m);
    }

    @Test
    void worstStateWinsAcrossAgents() {
        RecordingProvider provider = new RecordingProvider();
        StateMapping mapping = distinctMapping();
        Zone zone = new Zone("z", provider, new LightTarget("t", "t"), mapping);
        Engine engine = new Engine(zone, new Aggregator(), Duration.ofMinutes(5));
        Instant now = Instant.now();

        engine.onEvent(new StatusEvent(1, "s", "A", AgentState.RUNNING, now));
        assertEquals(mapping.lightFor(AgentState.RUNNING), provider.last);

        // A second agent hits an error -> the worst state (ERROR) must win.
        engine.onEvent(new StatusEvent(1, "s", "B", AgentState.ERROR, now));
        assertEquals(mapping.lightFor(AgentState.ERROR), provider.last);

        // That agent recovers to idle -> back to the remaining RUNNING agent.
        engine.onEvent(new StatusEvent(1, "s", "B", AgentState.IDLE, now));
        assertEquals(mapping.lightFor(AgentState.RUNNING), provider.last);
    }

    @Test
    void runningBecomesLongRunningPastThreshold() {
        RecordingProvider provider = new RecordingProvider();
        StateMapping mapping = distinctMapping();
        Zone zone = new Zone("z", provider, new LightTarget("t", "t"), mapping);
        // Tiny threshold so an event timestamped in the past is already "long running".
        Engine engine = new Engine(zone, new Aggregator(), Duration.ofSeconds(1));

        engine.onEvent(new StatusEvent(1, "s", "A", AgentState.RUNNING, Instant.now().minusSeconds(30)));
        assertEquals(mapping.lightFor(AgentState.LONG_RUNNING), provider.last);
    }

    @Test
    void successIsHeldAndIdleKeepsLastColour() {
        RecordingProvider provider = new RecordingProvider();
        StateMapping mapping = distinctMapping();
        Zone zone = new Zone("z", provider, new LightTarget("t", "t"), mapping);
        Engine engine = new Engine(zone, new Aggregator(), Duration.ofMinutes(5));

        // SUCCESS stamped long ago must still be SUCCESS (no decay to IDLE).
        engine.onEvent(new StatusEvent(1, "s", "A", AgentState.SUCCESS, Instant.now().minusSeconds(600)));
        assertEquals(mapping.lightFor(AgentState.SUCCESS), provider.last);

        // Session end (IDLE) keeps the last colour instead of switching off.
        engine.onEvent(new StatusEvent(1, "s", "A", AgentState.IDLE, Instant.now()));
        assertEquals(mapping.lightFor(AgentState.SUCCESS), provider.last);
    }

    @Test
    void reminderPulsesInCurrentColourUntilStateChanges() {
        RecordingProvider provider = new RecordingProvider();
        StateMapping mapping = distinctMapping();
        Zone zone = new Zone("z", provider, new LightTarget("t", "t"), mapping);
        Engine engine = new Engine(zone, new Aggregator(), Duration.ofMinutes(5));

        engine.onEvent(new StatusEvent(1, "s", "A", AgentState.SUCCESS, Instant.now()));
        engine.onEvent(StatusEvent.reminder("s", "A", Instant.now()));
        assertEquals(mapping.lightFor(AgentState.SUCCESS).withEffect(Effect.PULSE), provider.last);

        // A new prompt stops the pulse.
        engine.onEvent(new StatusEvent(1, "s", "A", AgentState.RUNNING, Instant.now()));
        assertEquals(mapping.lightFor(AgentState.RUNNING), provider.last);
    }
}
