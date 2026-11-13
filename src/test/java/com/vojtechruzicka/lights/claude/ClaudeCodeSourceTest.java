package com.vojtechruzicka.lights.claude;

import com.vojtechruzicka.lights.core.AgentState;
import com.vojtechruzicka.lights.core.StatusEvent;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ClaudeCodeSourceTest {

    @Test
    void mapsHookPayloadToStatusEvent() throws Exception {
        ClaudeCodeSource source = new ClaudeCodeSource(0);
        AtomicReference<StatusEvent> received = new AtomicReference<>();
        CountDownLatch latch = new CountDownLatch(1);
        source.start(event -> {
            received.set(event);
            latch.countDown();
        });

        try {
            String body = "{\"hook_event_name\":\"Notification\",\"session_id\":\"abc\"}";
            HttpRequest req = HttpRequest.newBuilder(
                            URI.create("http://127.0.0.1:" + source.boundPort() + "/event"))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(body))
                    .build();
            HttpResponse<String> res = HttpClient.newHttpClient()
                    .send(req, HttpResponse.BodyHandlers.ofString());

            assertEquals(200, res.statusCode());
            assertTrue(latch.await(2, TimeUnit.SECONDS), "event should be emitted");
            assertEquals(AgentState.WAITING_FOR_INPUT, received.get().state());
            assertEquals("abc", received.get().agentId());
            assertEquals(StatusEvent.CURRENT_SCHEMA_VERSION, received.get().schemaVersion());
        } finally {
            source.stop();
        }
    }

    @Test
    void idlePromptNotificationIsIgnoredButPermissionPromptWaits() {
        assertNull(ClaudeCodeSource.mapState("Notification", "idle_prompt"));
        assertTrue(ClaudeCodeSource.isReminder("Notification", "idle_prompt"));
        assertEquals(AgentState.ERROR, ClaudeCodeSource.mapState("StopFailure", null));
        assertEquals(AgentState.ERROR, ClaudeCodeSource.mapState("Notification", "quota_auto_resume_stale"));
        assertNull(ClaudeCodeSource.mapState("PostToolUseFailure", null));
        assertEquals(AgentState.WAITING_FOR_INPUT, ClaudeCodeSource.mapState("Notification", "permission_prompt"));
        assertEquals(AgentState.WAITING_FOR_INPUT, ClaudeCodeSource.mapState("Notification", null));
    }
}
