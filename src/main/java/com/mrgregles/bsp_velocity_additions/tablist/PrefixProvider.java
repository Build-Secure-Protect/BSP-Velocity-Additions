package com.mrgregles.bsp_velocity_additions.tablist;

import com.velocitypowered.api.proxy.Player;
import net.kyori.adventure.text.Component;

/** Where a player's rank prefix comes from. Empty when the network has no LuckPerms on the proxy. */
public interface PrefixProvider {

    PrefixProvider NONE = player -> Component.empty();

    /** The prefix as a component, with its own trailing space if it has text, or {@link Component#empty()}. */
    Component prefixOf(Player player);
}
