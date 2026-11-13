package com.vojtechruzicka.lights.cli;

import com.vojtechruzicka.lights.claude.ClaudeCodeSource;
import com.vojtechruzicka.lights.config.AppConfig;
import com.vojtechruzicka.lights.config.ConfigStore;
import com.vojtechruzicka.lights.core.Aggregator;
import com.vojtechruzicka.lights.core.StateMapping;
import com.vojtechruzicka.lights.core.Zone;
import com.vojtechruzicka.lights.daemon.Engine;
import com.vojtechruzicka.lights.hue.HueClient;
import com.vojtechruzicka.lights.hue.HueProvider;
import com.vojtechruzicka.lights.light.LightTarget;
import com.vojtechruzicka.lights.sound.SoundNotifier;
import picocli.CommandLine.Command;

import java.time.Duration;
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
        StateMapping mapping = StateMapping.fromConfig(cfg.colors());
        Zone zone = new Zone("agents", provider, target, mapping);

        Duration threshold = Duration.ofSeconds(cfg.daemon().longRunningSeconds());
        Engine engine = new Engine(zone, new Aggregator(), threshold);
        SoundNotifier sounds = SoundNotifier.fromConfig(cfg.sounds());
        engine.setStateChangeListener(sounds);
        int segments = cfg.maxSegments() != null ? cfg.maxSegments() : 1;
        engine.setMaxSegments(segments);
        engine.start();

        ClaudeCodeSource source = new ClaudeCodeSource(cfg.daemon().ingestPort());
        source.start(engine::onEvent);

        System.out.println("Daemon running.");
        System.out.println("  ingest:       http://127.0.0.1:" + cfg.daemon().ingestPort() + "/event");
        System.out.println("  monitor:      " + cfg.monitorTargetId());
        System.out.println("  long-running: " + cfg.daemon().longRunningSeconds() + "s");
        System.out.println("  segments:     " + (segments > 1 ? "up to " + segments + " sessions" : "off (one colour)"));
        System.out.println("  colors:       " + mapping.describe());
        System.out.println("  sounds:       " + (sounds.configured().isEmpty() ? "off" : sounds.configured()));
        System.out.println("Press Ctrl+C to stop.");

        CountDownLatch latch = new CountDownLatch(1);
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            source.stop();
            engine.stop();
            sounds.close();
            latch.countDown();
        }));
        latch.await();
        return 0;
    }
}
