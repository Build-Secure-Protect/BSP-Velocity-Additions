package com.mrgregles.bsp_velocity_additions.lastserver;

import java.util.Optional;
import java.util.UUID;

/** Remembers, per player, the server they were last connected to. */
public interface LastServerStore {

    Optional<String> get(UUID player);

    /** Records the server; {@code name} is kept only so a human can read the file. */
    void put(UUID player, String name, String server);

    /**
     * Forgets the player (a player reset, PR21), but only when their entry last changed before {@code changedBeforeMs}
     * (epoch ms): a player who has joined a server since the reset is kept. Doing it twice is harmless.
     *
     * @return the server that was forgotten; empty when nothing was on file or the entry is newer
     */
    Optional<String> remove(UUID player, long changedBeforeMs);

    int size();

    /** Writes pending changes now. Called when the proxy stops. */
    void flush();
}
