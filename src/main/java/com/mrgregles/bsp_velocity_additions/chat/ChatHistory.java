package com.mrgregles.bsp_velocity_additions.chat;

import com.mrgregles.bsp_velocity_additions.config.PluginConfig;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.List;

/**
 * The recent public chat of the whole network, for players joining it (task T25, decision D37). Only lines BSP-Core
 * relayed are kept, so team chat and blocked chat never are. In memory only: a proxy restart empties it. Keeps at most
 * {@code chatHistory.lines} lines, none older than {@code chatHistory.maxAgeMinutes}.
 */
public final class ChatHistory {

    /** One line as it arrived: when, from which server, and the payload it is rendered from for each viewer. */
    public record Line(Instant at, String server, RelayPayload payload) {
    }

    private final int maxLines;
    private final Duration maxAge;
    private final Clock clock;
    private final ArrayDeque<Line> lines = new ArrayDeque<>();

    public ChatHistory(int maxLines, Duration maxAge, Clock clock) {
        this.maxLines = maxLines;
        this.maxAge = maxAge;
        this.clock = clock;
    }

    public static ChatHistory of(PluginConfig.ChatHistory config, Clock clock) {
        return config.enabled() ? new ChatHistory(config.lines(), Duration.ofMinutes(config.maxAgeMinutes()), clock) : off();
    }

    /** Keeps nothing. */
    public static ChatHistory off() {
        return new ChatHistory(0, Duration.ZERO, Clock.systemUTC());
    }

    public synchronized void add(String server, RelayPayload payload) {
        if (maxLines <= 0) {
            return;
        }
        lines.addLast(new Line(clock.instant(), server, payload));
        while (lines.size() > maxLines) {
            lines.removeFirst();
        }
    }

    /** The kept lines, oldest first, without those older than the age limit. */
    public synchronized List<Line> recent() {
        Instant cutoff = clock.instant().minus(maxAge);
        lines.removeIf(line -> line.at().isBefore(cutoff));
        return List.copyOf(lines);
    }
}
