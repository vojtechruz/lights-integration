package com.vojtechruzicka.lights.cli;

import com.vojtechruzicka.lights.config.AppConfig;
import com.vojtechruzicka.lights.config.ConfigStore;
import com.vojtechruzicka.lights.hue.HueClient;
import com.vojtechruzicka.lights.hue.HueDiscovery;
import com.vojtechruzicka.lights.hue.HueProvider;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

import java.net.InetAddress;
import java.util.List;
import java.util.concurrent.Callable;

/** Discovers the Hue bridge and pairs with it (requires pressing the link button). */
@Command(
        name = "pair",
        mixinStandardHelpOptions = true,
        description = "Discover the Hue bridge and pair (press the link button first).")
public final class PairCommand implements Callable<Integer> {

    @Option(names = "--ip", description = "Bridge IP address (skips cloud discovery).")
    String ip;

    @Option(names = "--timeout", defaultValue = "60",
            description = "Seconds to wait for the link button (default: ${DEFAULT-VALUE}).")
    int timeoutSeconds;

    @Override
    public Integer call() throws Exception {
        ConfigStore store = new ConfigStore();

        String bridgeIp = ip;
        if (bridgeIp == null) {
            System.out.println("Discovering Hue bridge via cloud...");
            List<HueDiscovery.Bridge> bridges = new HueDiscovery().discover();
            if (bridges.isEmpty()) {
                System.err.println("No bridge found. Re-run with --ip <bridge-ip>.");
                return 1;
            }
            bridges.forEach(b -> System.out.println("  found bridge " + b.id() + " at " + b.ip()));
            bridgeIp = bridges.getFirst().ip();
            System.out.println("Using bridge at " + bridgeIp);
        }

        HueClient client = new HueClient(bridgeIp);
        System.out.println("Press the link button on the bridge now (waiting up to " + timeoutSeconds + "s)...");

        HueClient.Credentials creds = null;
        long deadline = System.nanoTime() + timeoutSeconds * 1_000_000_000L;
        while (System.nanoTime() < deadline) {
            creds = client.createUser("lights-integration", deviceName());
            if (creds != null) {
                break;
            }
            System.out.print(".");
            System.out.flush();
            Thread.sleep(2000);
        }
        System.out.println();

        if (creds == null) {
            System.err.println("Timed out. Press the link button, then run 'pair' again.");
            return 1;
        }

        AppConfig cfg = store.loadOrDefaults()
                .withHue(new AppConfig.HueConfig(bridgeIp, creds.username(), creds.clientKey()));
        store.save(cfg);
        System.out.println("Paired! Saved bridge + key to " + store.path());

        System.out.println("\nAvailable light targets:");
        HueProvider provider = new HueProvider(client, creds.username());
        provider.listTargets().forEach(t -> System.out.println("  " + t.id() + "   " + t.name()));

        System.out.println("\nNext: pick your monitor light's id above, then verify it with:");
        System.out.println("  ./gradlew run --args=\"test --target <id>\"");
        return 0;
    }

    /** Bridge device name is limited to 19 chars. */
    private static String deviceName() {
        String host;
        try {
            host = InetAddress.getLocalHost().getHostName();
        } catch (Exception e) {
            host = "device";
        }
        return host.length() > 19 ? host.substring(0, 19) : host;
    }
}
