package com.vojtechruzicka.lights.core;

import com.vojtechruzicka.lights.light.RgbColor;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

class StateMappingTest {

    @Test
    void configOverridesOnlyListedStates() {
        Map<String, StateMapping.ColorSpec> cfg = new LinkedHashMap<>();
        cfg.put("RUNNING", new StateMapping.ColorSpec("#00FFFF", 40));
        cfg.put("SUCCESS", new StateMapping.ColorSpec("off", null));
        cfg.put("ERROR", new StateMapping.ColorSpec(null, 100));   // brightness only
        cfg.put("BOGUS", new StateMapping.ColorSpec("#FFFFFF", 50)); // unknown state: skipped
        cfg.put("LONG_RUNNING", new StateMapping.ColorSpec("purple", 50)); // bad colour: skipped

        StateMapping m = StateMapping.fromConfig(cfg);
        StateMapping d = StateMapping.defaults();

        assertEquals(new RgbColor(0, 255, 255), m.lightFor(AgentState.RUNNING).color());
        assertEquals(40, m.lightFor(AgentState.RUNNING).brightnessPercent());
        assertFalse(m.lightFor(AgentState.SUCCESS).on());
        assertEquals(d.lightFor(AgentState.ERROR).color(), m.lightFor(AgentState.ERROR).color());
        assertEquals(100, m.lightFor(AgentState.ERROR).brightnessPercent());
        assertEquals(d.lightFor(AgentState.LONG_RUNNING), m.lightFor(AgentState.LONG_RUNNING));
        assertEquals(d.lightFor(AgentState.WAITING_FOR_INPUT), m.lightFor(AgentState.WAITING_FOR_INPUT));
    }

    @Test
    void parsesHexWithOrWithoutHash() {
        assertEquals(new RgbColor(255, 140, 0), RgbColor.parse("#FF8C00"));
        assertEquals(new RgbColor(255, 140, 0), RgbColor.parse("ff8c00"));
        assertEquals("#FF8C00", new RgbColor(255, 140, 0).hex());
    }
}
