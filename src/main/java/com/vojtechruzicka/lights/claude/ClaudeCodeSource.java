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
        String notificationType = node.path("notification_type").asText(null);
        if (isReminder(eventName, notificationType)) {
            return StatusEvent.reminder(id(), agentId, Instant.now());
        }
        AgentState state = mapState(eventName, notificationType);
        if (state == null) {
            return null;
        }
        if (state == AgentState.ERROR) {
            log.info("error event {} for {}: {} {}", eventName, agentId,
                    node.path("error").asText(notificationType), node.path("error_details").asText(""));
        }
        return new StatusEvent(StatusEvent.CURRENT_SCHEMA_VERSION, id(), agentId, state, Instant.now());
    }

    /**
     * {@code idle_prompt} is a "still there?" notification sent ~60 s after Claude finished.
     * It is not a state change; the agent keeps its state and the light starts pulsing.
     */
    static boolean isReminder(String eventName, String notificationType) {
        return "Notification".equals(eventName) && "idle_prompt".equals(notificationType);
    }

    /**
     * Maps a Claude Code hook event to a state, or {@code null} to ignore it.
     * {@code StopFailure} (turn ended by an API error: rate limit, billing, auth, overload...)
     * and usage-limit notifications ({@code quota_auto_resume_*}) are errors. Ordinary tool
     * failures ({@code PostToolUseFailure}) deliberately are not: Claude routinely recovers.
     */
    static AgentState mapState(String eventName, String notificationType) {
        if (eventName == null) {
            return null;
        }
        return switch (eventName) {
            case "UserPromptSubmit", "PreToolUse", "PostToolUse", "SubagentStop" -> AgentState.RUNNING;
            case "StopFailure" -> AgentState.ERROR;
            case "Notification" -> {
                if (isReminder(eventName, notificationType)) yield null;
                if (notificationType != null && notificationType.startsWith("quota_auto_resume")) yield AgentState.ERROR;
                yield AgentState.WAITING_FOR_INPUT;
            }
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
