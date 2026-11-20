package com.mrgregles.bsp_velocity_additions.store;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/** At most {@code max} failed {@code /store link} and {@code /store claim} tries per player in any ten minutes. */
public final class FailedTries {

    public static final Duration WINDOW = Duration.ofMinutes(10);

    private final int max;
    private final Clock clock;
    private final Map<UUID, Deque<Instant>> failures = new ConcurrentHashMap<>();

    public FailedTries(int max, Clock clock) {
        this.max = max;
        this.clock = clock;
    }

    /** False once the player has {@code max} failures in the last ten minutes. */
    public boolean mayTry(UUID player) {
        Deque<Instant> mine = failures.get(player);
        if (mine == null) {
            return true;
        }
        synchronized (mine) {
            prune(mine);
            return mine.size() < max;
        }
    }

    public void fail(UUID player) {
        Deque<Instant> mine = failures.computeIfAbsent(player, k -> new ArrayDeque<>());
        synchronized (mine) {
            prune(mine);
            mine.addLast(clock.instant());
        }
    }

    private void prune(Deque<Instant> mine) {
        Instant cutoff = clock.instant().minus(WINDOW);
        while (!mine.isEmpty() && !mine.peekFirst().isAfter(cutoff)) {
            mine.removeFirst();
        }
    }
}
