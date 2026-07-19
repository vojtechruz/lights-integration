package com.vojtechruzicka.lights.cli;

import com.vojtechruzicka.lights.claude.ClaudeCodeSource;
import com.vojtechruzicka.lights.config.AppConfig;
import com.vojtechruzicka.lights.config.ConfigStore;
import com.vojtechruzicka.lights.core.AgentState;
import com.vojtechruzicka.lights.core.Aggregator;
import com.vojtechruzicka.lights.core.StateMapping;
import com.vojtechruzicka.lights.core.Zone;
import com.vojtechruzicka.lights.daemon.Engine;
import com.vojtechruzicka.lights.hue.HueClient;
import com.vojtechruzicka.lights.hue.HueProvider;
import com.vojtechruzicka.lights.light.LightState;
import com.vojtechruzicka.lights.light.LightTarget;
import com.vojtechruzicka.lights.light.RgbColor;
import picocli.CommandLine.Command;

import java.time.Duration;
import java.util.EnumMap;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Callable;

/** Starts the long-running daemon: HTTP ingest + state engine + Hue output. */
@Command(
        name = "run",
        mixinStandardHelpOptions = true,
        description = "Start the daemon (HTTP ingest + state engine + Hue output).")
public final class RunCommand implements Callable<Integer> {

    @Override
    public Integer call() throws Exception {
        ConfigStore store = new ConfigStore();
        AppConfig cfg = store.loadOrDefaults();

        if (cfg.hue() == null || cfg.hue().appKey() == null) {
            System.err.println("Not paired yet. Run 'pair' first.");
            return 1;
        }
        if (cfg.monitorTargetId() == null) {
            System.err.println("No monitor target set. Pick one with:");
            System.err.println("  ./gradlew run --args=\"test --target <id> --save\"");
            return 1;
        }

        HueClient client = new HueClient(cfg.hue().bridgeIp());
        HueProvider provider = new HueProvider(client, cfg.hue().appKey());
        LightTarget target = new LightTarget(cfg.monitorTargetId(), "monitor");
        Zone zone = new Zone("agents", provider, target, defaultMapping());

        Duration threshold = Duration.ofSeconds(cfg.daemon().longRunningSeconds());
        Engine engine = new Engine(zone, new Aggregator(), threshold);
        engine.start();

        ClaudeCodeSource source = new ClaudeCodeSource(cfg.daemon().ingestPort());
        source.start(engine::onEvent);

        System.out.println("Daemon running.");
        System.out.println("  ingest:       http://127.0.0.1:" + cfg.daemon().ingestPort() + "/event");
        System.out.println("  monitor:      " + cfg.monitorTargetId());
        System.out.println("  long-running: " + cfg.daemon().longRunningSeconds() + "s");
        System.out.println("Press Ctrl+C to stop.");

        CountDownLatch latch = new CountDownLatch(1);
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            source.stop();
            engine.stop();
            latch.countDown();
        }));
        latch.await();
        return 0;
    }

    /** Default state → colour mapping. Will become config-driven (see roadmap). */
    private static StateMapping defaultMapping() {
        Map<AgentState, LightState> m = new EnumMap<>(AgentState.class);
        m.put(AgentState.ERROR, LightState.solid(new RgbColor(255, 0, 0), 80));
        m.put(AgentState.WAITING_FOR_INPUT, LightState.solid(new RgbColor(255, 140, 0), 80));
        m.put(AgentState.LONG_RUNNING, LightState.solid(new RgbColor(160, 0, 255), 80));
        m.put(AgentState.RUNNING, LightState.solid(new RgbColor(0, 60, 255), 70));
        m.put(AgentState.SUCCESS, LightState.solid(new RgbColor(0, 255, 60), 90));
        m.put(AgentState.IDLE, LightState.off());
        return new StateMapping(m);
    }
}
