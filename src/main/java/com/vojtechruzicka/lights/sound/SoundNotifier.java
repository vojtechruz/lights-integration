package com.vojtechruzicka.lights.sound;

import com.vojtechruzicka.lights.core.AgentState;
import com.vojtechruzicka.lights.core.StateChangeListener;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.sound.sampled.AudioInputStream;
import javax.sound.sampled.AudioSystem;
import javax.sound.sampled.Clip;
import javax.sound.sampled.LineEvent;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.EnumMap;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/**
 * Optional sound output: plays a WAV file when an agent enters a state that has a sound
 * configured (typically SUCCESS, WAITING_FOR_INPUT, ERROR). Playback runs on its own
 * thread so it never delays the light or the hook ingest. Sounds are played one after
 * another, and the same sound is not repeated within {@link #MIN_GAP}.
 */
public final class SoundNotifier implements StateChangeListener, AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(SoundNotifier.class);
    static final Duration MIN_GAP = Duration.ofSeconds(2);

    private final Map<AgentState, Path> sounds;
    private final Map<AgentState, Instant> lastPlayed = new EnumMap<>(AgentState.class);
    private final ExecutorService player = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "sound-player");
        t.setDaemon(true);
        return t;
    });

    public SoundNotifier(Map<AgentState, Path> sounds) {
        this.sounds = new EnumMap<>(AgentState.class);
        sounds.forEach((state, path) -> {
            if (Files.isRegularFile(path)) {
                this.sounds.put(state, path);
            } else {
                log.warn("sound for {} not found, skipping: {}", state, path);
            }
        });
    }

    /** Builds a notifier from config ({@code state name -> wav path}); unknown states are skipped. */
    public static SoundNotifier fromConfig(Map<String, String> config) {
        Map<AgentState, Path> map = new EnumMap<>(AgentState.class);
        if (config != null) {
            config.forEach((state, path) -> {
                try {
                    if (path != null && !path.isBlank()) {
                        map.put(AgentState.valueOf(state), Path.of(path));
                    }
                } catch (IllegalArgumentException e) {
                    log.warn("unknown state in sounds config: {}", state);
                }
            });
        }
        return new SoundNotifier(map);
    }

    public Map<AgentState, Path> configured() {
        return Map.copyOf(sounds);
    }

    @Override
    public void onStateChange(String agentId, AgentState from, AgentState to) {
        Path path = sounds.get(to);
        if (path == null || to == from) {
            return;
        }
        synchronized (lastPlayed) {
            Instant now = Instant.now();
            Instant last = lastPlayed.get(to);
            if (last != null && Duration.between(last, now).compareTo(MIN_GAP) < 0) {
                return;
            }
            lastPlayed.put(to, now);
        }
        player.submit(() -> play(path));
    }

    private static void play(Path path) {
        try (AudioInputStream in = AudioSystem.getAudioInputStream(path.toFile());
             Clip clip = AudioSystem.getClip()) {
            CountDownLatch done = new CountDownLatch(1);
            clip.addLineListener(e -> {
                if (e.getType() == LineEvent.Type.STOP) {
                    done.countDown();
                }
            });
            clip.open(in);
            clip.start();
            done.await(10, TimeUnit.SECONDS);
        } catch (Exception e) {
            log.warn("cannot play {}: {}", path, e.getMessage());
        }
    }

    @Override
    public void close() {
        player.shutdownNow();
    }
}
