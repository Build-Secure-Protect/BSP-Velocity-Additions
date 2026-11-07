package com.mrgregles.bsp_velocity_additions.tablist;

import com.velocitypowered.api.proxy.Player;
import com.velocitypowered.api.proxy.ProxyServer;

/**
 * Bug B6: reloads every online player's rank from LuckPerms' storage every {@code tabList.rankRefreshSeconds}, so a
 * rank changed on a backend while the player is online shows in the tab list and {@code /players} without a relog.
 * The tab list reads the prefix again on its own next refresh.
 */
public final class RankRefresher {

    private final ProxyServer proxy;
    private final PrefixProvider prefixes;

    public RankRefresher(ProxyServer proxy, PrefixProvider prefixes) {
        this.proxy = proxy;
        this.prefixes = prefixes;
    }

    public void reloadAll() {
        for (Player player : proxy.getAllPlayers()) {
            if (player.getCurrentServer().isPresent()) {
                prefixes.reload(player);
            }
        }
    }
}
