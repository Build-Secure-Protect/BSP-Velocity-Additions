package com.mrgregles.bsp_velocity_additions.lastserver;

import java.util.Optional;
import java.util.UUID;

/** Remembers, per player, the server they were last connected to. */
public interface LastServerStore {

    Optional<String> get(UUID player);

    /** Records the server; {@code name} is kept only so a human can read the file. */
    void put(UUID player, String name, String server);

    int size();

    /** Writes pending changes now. Called when the proxy stops. */
    void flush();
}
