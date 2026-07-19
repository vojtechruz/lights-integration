package com.vojtechruzicka.lights.core;

import java.util.function.Consumer;

/**
 * Produces {@link StatusEvent}s. The first implementation ({@code ClaudeCodeSource}) is
 * push-based (fed by an HTTP ingest), but the interface also fits future poll-based
 * sources. This is the seam for supporting other agent providers.
 */
public interface StatusSource {

    String id();

    /** Begin producing events into {@code sink}. */
    void start(Consumer<StatusEvent> sink) throws Exception;

    void stop() throws Exception;
}
