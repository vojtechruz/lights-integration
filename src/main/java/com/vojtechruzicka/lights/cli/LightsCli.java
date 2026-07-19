package com.vojtechruzicka.lights.cli;

import picocli.CommandLine;
import picocli.CommandLine.Command;

/**
 * Entry point. {@code lights-integration} is a CLI that launches a long-running daemon
 * which watches AI agent state (via Claude Code hooks) and drives Philips Hue lights.
 *
 * <p>Subcommands:
 * <ul>
 *   <li>{@code pair} — discover the Hue bridge and pair (link button), storing IP + key</li>
 *   <li>{@code test} — cycle colours on the configured lights to verify control</li>
 *   <li>{@code run}  — start the daemon (HTTP ingest + state engine + Hue output)</li>
 * </ul>
 */
@Command(
        name = "lights-integration",
        mixinStandardHelpOptions = true,
        version = "lights-integration 0.1.0-SNAPSHOT",
        description = "Drive Philips Hue lights from AI agent activity.",
        subcommands = {
                PairCommand.class,
                TestCommand.class,
                RunCommand.class,
        })
public final class LightsCli implements Runnable {

    @Override
    public void run() {
        // No subcommand given: show usage.
        CommandLine.usage(this, System.out);
    }

    public static void main(String[] args) {
        // The Hue bridge's self-signed cert has no SAN and CN=<bridge id>, so hostname
        // verification against its IP fails. HttpClient only honours this switch if it is
        // set before the first HttpClient is created anywhere in the process (discovery
        // creates one), so it must be the very first thing we do. LAN-only; see roadmap
        // for cert pinning as the proper hardening.
        System.setProperty("jdk.internal.httpclient.disableHostnameVerification", "true");

        int exitCode = new CommandLine(new LightsCli()).execute(args);
        System.exit(exitCode);
    }
}
