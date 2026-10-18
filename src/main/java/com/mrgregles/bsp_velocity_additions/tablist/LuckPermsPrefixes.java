package com.mrgregles.bsp_velocity_additions.tablist;

import com.velocitypowered.api.proxy.Player;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import org.slf4j.Logger;

import java.util.Optional;

/**
 * Prefixes from LuckPerms-Velocity (decision D4). Everything that touches the LuckPerms API sits in the nested
 * {@link Bridge} class, so nothing of it loads when the plugin is absent (the hard-won rule for optional mods).
 */
public final class LuckPermsPrefixes {

    private LuckPermsPrefixes() {
    }

    /** The LuckPerms-backed provider when LuckPerms is loaded on the proxy, otherwise {@link PrefixProvider#NONE}. */
    public static PrefixProvider detect(Logger logger) {
        try {
            Class.forName("net.luckperms.api.LuckPermsProvider");
            PrefixProvider p = Bridge.create();
            logger.info("LuckPerms found on the proxy; rank prefixes will show in the tab list and /players");
            return p;
        } catch (ClassNotFoundException | LinkageError | IllegalStateException e) {
            logger.info("LuckPerms is not on the proxy; names show without rank prefixes");
            return PrefixProvider.NONE;
        }
    }

    static final class Bridge {
        private static final LegacyComponentSerializer LEGACY = LegacyComponentSerializer.legacyAmpersand();

        static PrefixProvider create() {
            net.luckperms.api.LuckPerms api = net.luckperms.api.LuckPermsProvider.get(); // throws IllegalStateException when not loaded
            return player -> {
                try {
                    String prefix = Optional.ofNullable(api.getPlayerAdapter(Player.class).getMetaData(player).getPrefix()).orElse("");
                    return prefix.isBlank() ? Component.empty() : LEGACY.deserialize(prefix).append(Component.text(" "));
                } catch (RuntimeException e) {
                    return Component.empty(); // a player LuckPerms has not loaded yet
                }
            };
        }
    }
}
