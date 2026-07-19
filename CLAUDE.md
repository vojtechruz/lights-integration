# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## What this is

A background tool that watches AI agent activity (Claude Code, via hooks) and drives
**Philips Hue** lights to reflect state (working / waiting for input / error / …). It runs
as a long-running **daemon started from a CLI**. Full design and roadmap live in
[docs/feature-analysis.md](docs/feature-analysis.md) — read it before making architectural
changes.

## Build / run

- Java **25** (latest LTS). It is auto-provisioned by the Gradle **foojay** toolchain
  resolver, so you do not need JDK 25 on PATH — Gradle downloads it.
- Gradle **9.6.1** via the committed wrapper. Always use `./gradlew`, not a system Gradle.

```bash
./gradlew build                       # compile + test + assemble
./gradlew test                        # run tests
./gradlew test --tests '*ClassName*'  # run a single test class
./gradlew run --args="--help"         # run the CLI
./gradlew run --args="pair"           # a subcommand (pair / test / run)
./gradlew installDist                 # produces build/install/lights-integration/bin
```

Note: `*.jar` is gitignored, with an explicit exception for `gradle/wrapper/gradle-wrapper.jar`.
The runtime config (holds the Hue key) lives under `/config/` and is gitignored — never
commit it.

## Architecture (big picture)

The core is deliberately vendor-neutral: it knows nothing about Claude or Hue directly,
only about abstractions. This keeps the two planned expansion seams clean — another agent
provider, and another light provider.

```
StatusSource(s) ──► StatusEvent ──► Aggregator ──► Zone ──► Mapping ──► LightProvider(s)
(ClaudeCodeSource)   (schemaVersion)  (worst-wins)          (state→LightState)  (HueProvider)
```

- **StatusSource** — produces `StatusEvent`s. First impl: `ClaudeCodeSource`, fed by Claude
  Code hooks that POST to a local HTTP ingest bound to `127.0.0.1`.
- **Aggregator** — with multiple agents, the light shows the *worst* state any one agent is
  in. Severity order: `ERROR > WAITING_FOR_INPUT > LONG_RUNNING > RUNNING > SUCCESS > IDLE`.
  `LONG_RUNNING` is not an event — the daemon derives it from a timer (configurable,
  default 5 min). `SUCCESS` is a transient flash.
- **Zone** — binds a set of sources to a light target + a state→colour mapping. MVP has one
  zone (agents → monitor light). Later zones (e.g. Pomodoro) reuse the same machinery.
- **LightProvider / LightTarget / LightState** — vendor-neutral output. First impl:
  `HueProvider` (CLIP v2 over HTTPS; pairing via the v1 link-button flow).

Package root: `com.vojtechruzicka.lights`. CLI entry: `cli.LightsCli`.

## Key decisions / gotchas

- **Single writer to Hue**: only this daemon writes to the bridge; everything else (even a
  future Pomodoro integration) feeds it via the ingest API, to avoid two apps fighting over
  the bridge.
- **Hooks are a dumb pipe**: they forward the event JSON to the daemon and always exit 0
  (a failing hook must never block Claude Code, especially `PreToolUse`). Schema changes
  live in the daemon, so hooks rarely need reinstalling.
- **Bake-in-now (cheap now, costly later), even before open-sourcing**: versioned event
  schema (`schemaVersion`), config-driven states/mapping (not a hardcoded enum wired into
  logic), and binding the ingest endpoint to `127.0.0.1` only.
- Confirm with the user before editing their Claude Code `settings.json` to install hooks.

## When adding code

- Use current Hue API details from docs when implementing the Hue provider (discovery,
  link-button pairing returning `username` + `clientkey`, CLIP v2 light control). The bridge
  serves HTTPS with a self-signed cert — handle that explicitly.
- Respect Hue rate limits (~10 commands/s per light): debounce and coalesce updates.
