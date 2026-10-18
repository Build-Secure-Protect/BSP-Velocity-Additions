package com.mrgregles.bsp_velocity_additions.tablist;

import com.mrgregles.bsp_velocity_additions.config.Messages;
import com.mrgregles.bsp_velocity_additions.config.PluginConfig;
import com.velocitypowered.api.proxy.Player;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;

/** Renders one player's name the way the tab list and {@code /players} both show it: {@code tabList.entry}. */
public final class NameFormatter {

    public static final String KEY_ENTRY = "tabList.entry";
    public static final String KEY_STAFF_MARK = "tabList.staffMark";

    private final PluginConfig.TabList config;
    private final Messages messages;
    private final PrefixProvider prefixes;

    public NameFormatter(PluginConfig.TabList config, Messages messages, PrefixProvider prefixes) {
        this.config = config;
        this.messages = messages;
        this.prefixes = prefixes;
    }

    public Component nameOf(Player player) {
        boolean staff = config.hasStaffPermission() && player.hasPermission(config.staffPermission());
        Component mark = staff ? messages.render(KEY_STAFF_MARK) : Component.empty();
        return messages.render(KEY_ENTRY,
                Placeholder.unparsed("player", player.getUsername()),
                Placeholder.component("prefix", prefixes.prefixOf(player)),
                Placeholder.component("staff", mark));
    }
}
