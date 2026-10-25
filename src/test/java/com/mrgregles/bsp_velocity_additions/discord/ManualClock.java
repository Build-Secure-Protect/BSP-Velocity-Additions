package com.mrgregles.bsp_velocity_additions.discord;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.function.LongSupplier;

/** A clock the test turns by hand, and a {@link Delayer} that runs tasks when the clock reaches them. */
final class ManualClock implements LongSupplier, Delayer {

    private record Task(long due, long order, Runnable run) {
    }

    private final List<Task> tasks = new ArrayList<>();
    private long now;
    private long order;

    ManualClock(long start) {
        this.now = start;
    }

    @Override
    public long getAsLong() {
        return now;
    }

    @Override
    public void after(long millis, Runnable task) {
        tasks.add(new Task(now + Math.max(0L, millis), order++, task));
    }

    /** Runs every task due now, including the ones those tasks add for now. */
    void runDue() {
        while (true) {
            Task next = tasks.stream().filter(t -> t.due() <= now)
                    .min(Comparator.comparingLong(Task::due).thenComparingLong(Task::order)).orElse(null);
            if (next == null) {
                return;
            }
            tasks.remove(next);
            next.run().run();
        }
    }

    /** Moves the clock on, running each task at its own time. */
    void advance(long millis) {
        long end = now + millis;
        while (true) {
            Task next = tasks.stream().filter(t -> t.due() <= end)
                    .min(Comparator.comparingLong(Task::due).thenComparingLong(Task::order)).orElse(null);
            if (next == null) {
                break;
            }
            now = Math.max(now, next.due());
            runDue();
        }
        now = end;
    }

    int pending() {
        return tasks.size();
    }
}
