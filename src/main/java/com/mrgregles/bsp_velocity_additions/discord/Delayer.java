package com.mrgregles.bsp_velocity_additions.discord;

/** Runs the bot's work on its own thread, now or after a delay. The tests use a hand-turned clock instead. */
public interface Delayer {

    void after(long millis, Runnable task);

    default void now(Runnable task) {
        after(0L, task);
    }
}
