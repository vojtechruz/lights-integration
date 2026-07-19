package com.vojtechruzicka.lights.cli;

import com.vojtechruzicka.lights.config.AppConfig;
import com.vojtechruzicka.lights.config.ConfigStore;
import com.vojtechruzicka.lights.hue.HueClient;
import com.vojtechruzicka.lights.hue.HueProvider;
import com.vojtechruzicka.lights.light.LightState;
import com.vojtechruzicka.lights.light.LightTarget;
import com.vojtechruzicka.lights.light.RgbColor;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

import java.util.List;
import java.util.concurrent.Callable;

/** Cycles colours on a target to verify control works. */
@Command(
        name = "test",
        mixinStandardHelpOptions = true,
        description = "Cycle colours on the configured light to verify control.")
public final class TestCommand implements Callable<Integer> {

    @Option(names = "--target", description = "Target id (default: the configured monitor target).")
    String target;

    @Option(names = "--save", description = "Save this target as the monitor target in config.")
    boolean save;

    private record Step(String label, RgbColor color) {}

    @Override
    public Integer call() throws Exception {
        ConfigStore store = new ConfigStore();
        AppConfig cfg = store.loadOrDefaults();
        if (cfg.hue() == null || cfg.hue().appKey() == null) {
            System.err.println("Not paired yet. Run 'pair' first.");
            return 1;
        }

        HueClient client = new HueClient(cfg.hue().bridgeIp());
        HueProvider provider = new HueProvider(client, cfg.hue().appKey());

        String targetId = target != null ? target : cfg.monitorTargetId();
        if (targetId == null) {
            System.out.println("No --target given and no monitor target configured. Available:");
            provider.listTargets().forEach(t -> System.out.println("  " + t.id() + "   " + t.name()));
            return 1;
        }

        LightTarget lightTarget = new LightTarget(targetId, targetId);
        List<Step> steps = List.of(
                new Step("RED (error)", new RgbColor(255, 0, 0)),
                new Step("ORANGE (waiting for input)", new RgbColor(255, 140, 0)),
                new Step("PURPLE (long running)", new RgbColor(160, 0, 255)),
                new Step("BLUE (running)", new RgbColor(0, 60, 255)),
                new Step("GREEN (success)", new RgbColor(0, 255, 60)),
                new Step("WHITE (idle)", new RgbColor(255, 240, 220)));

        for (Step s : steps) {
            System.out.println("-> " + s.label());
            provider.apply(lightTarget, LightState.solid(s.color(), 80));
            Thread.sleep(1500);
        }
        System.out.println("Done.");

        if (save) {
            store.save(cfg.withMonitorTargetId(targetId));
            System.out.println("Saved monitor target: " + targetId);
        }
        return 0;
    }
}
