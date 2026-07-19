package com.vojtechruzicka.lights.claude;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import com.vojtechruzicka.lights.core.AgentState;
import com.vojtechruzicka.lights.core.StatusEvent;
import com.vojtechruzicka.lights.core.StatusSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.concurrent.Executors;
import java.util.function.Consumer;

/**
 * {@link StatusSource} fed by Claude Code hooks. Runs a tiny HTTP ingest bound to
 * {@code 127.0.0.1} only; hooks POST their raw event JSON to {@code /event}. The request
 * is acknowledged immediately (the hook must never be blocked), then mapped to a
 * {@link StatusEvent}.
 */
public final class ClaudeCodeSource implements StatusSource {

    private static final Logger log = LoggerFactory.getLogger(ClaudeCodeSource.class);
    private static final String BIND_ADDRESS = "127.0.0.1";

    private final int port;
    private final ObjectMapper mapper = new ObjectMapper();
    private HttpServer server;

    public ClaudeCodeSource(int port) {
        this.port = port;
    }

    @Override
    public String id() {
        return "claude-code";
    }

    @Override
    public void start(Consumer<StatusEvent> sink) throws IOException {
        server = HttpServer.create(new InetSocketAddress(InetAddress.getByName(BIND_ADDRESS), port), 0);
        server.createContext("/event", exchange -> handleEvent(exchange, sink));
        server.createContext("/health", exchange -> respond(exchange, 200, "ok"));
        server.setExecutor(Executors.newFixedThreadPool(2));
        server.start();
        log.info("Claude Code ingest listening on http://{}:{}/event", BIND_ADDRESS, port);
    }

    @Override
    public void stop() {
        if (server != null) {
            server.stop(0);
        }
    }

    /** Actual bound port (useful when constructed with port 0 for tests). */
    public int boundPort() {
        return server != null ? server.getAddress().getPort() : port;
    }

    private void handleEvent(HttpExchange exchange, Consumer<StatusEvent> sink) {
        try {
            if (!"POST".equalsIgnoreCase(exchange.getRequestMethod())) {
                respond(exchange, 405, "method not allowed");
                return;
            }
            byte[] body = exchange.getRequestBody().readAllBytes();
            respond(exchange, 200, "ok"); // ack before doing any work

            StatusEvent event = parse(body);
            if (event != null) {
                sink.accept(event);
            }
        } catch (Exception e) {
            log.warn("ingest error: {}", e.getMessage());
        }
    }

    private StatusEvent parse(byte[] body) throws IOException {
        JsonNode node = mapper.readTree(body);
        String eventName = node.path("hook_event_name").asText(null);
        String agentId = node.path("session_id").asText("default");
        AgentState state = mapState(eventName);
        if (state == null) {
            return null;
        }
        return new StatusEvent(StatusEvent.CURRENT_SCHEMA_VERSION, id(), agentId, state, Instant.now());
    }

    /** Maps a Claude Code hook event name to a state, or {@code null} to ignore it. */
    private static AgentState mapState(String eventName) {
        if (eventName == null) {
            return null;
        }
        return switch (eventName) {
            case "UserPromptSubmit", "PreToolUse", "PostToolUse", "SubagentStop" -> AgentState.RUNNING;
            case "Notification" -> AgentState.WAITING_FOR_INPUT;
            case "Stop" -> AgentState.SUCCESS;
            case "SessionStart", "SessionEnd" -> AgentState.IDLE;
            default -> null;
        };
    }

    private static void respond(HttpExchange exchange, int code, String message) throws IOException {
        byte[] bytes = message.getBytes(StandardCharsets.UTF_8);
        exchange.sendResponseHeaders(code, bytes.length);
        try (OutputStream os = exchange.getResponseBody()) {
            os.write(bytes);
        }
    }
}
