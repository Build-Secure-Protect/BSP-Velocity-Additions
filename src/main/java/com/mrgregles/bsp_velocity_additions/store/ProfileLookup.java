package com.mrgregles.bsp_velocity_additions.store;

import java.util.Optional;
import java.util.UUID;

/** Finds a Minecraft Java account by name. */
public interface ProfileLookup {

    /** A Mojang account: its UUID and its correctly cased name. */
    record Profile(UUID uuid, String name) {
        public Profile {
            if (!Commands.NAME.matcher(name).matches()) {
                throw new IllegalArgumentException("not a valid player name");
            }
        }
    }

    /** Mojang could not be asked right now (rate limited, down, no network); worth trying again later. */
    final class Unavailable extends Exception {
        public Unavailable(String message) {
            super(message);
        }
    }

    /**
     * The account with this name, or empty when there is none or the name is not a valid Java username
     * ({@code ^[A-Za-z0-9_]{3,16}$} after trimming).
     */
    Optional<Profile> find(String name) throws Unavailable;
}
