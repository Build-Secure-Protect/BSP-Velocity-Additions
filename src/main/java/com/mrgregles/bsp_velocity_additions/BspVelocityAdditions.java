package com.mrgregles.bsp_velocity_additions;

import com.google.inject.Inject;
import com.mrgregles.bsp_velocity_additions.config.Messages;
import com.mrgregles.bsp_velocity_additions.config.PluginConfig;
import com.mrgregles.bsp_velocity_additions.chat.ChatRelayFeature;
import com.mrgregles.bsp_velocity_additions.joinleave.JoinLeaveFeature;
import com.mrgregles.bsp_velocity_additions.players.PlayersCommand;
import com.mrgregles.bsp_velocity_additions.tablist.LuckPermsPrefixes;
import com.mrgregles.bsp_velocity_additions.tablist.NameFormatter;
import com.mrgregles.bsp_velocity_additions.tablist.PrefixProvider;
import com.mrgregles.bsp_velocity_additions.tablist.TabListFeature;
import com.velocitypowered.api.command.CommandMeta;
import com.mrgregles.bsp_velocity_additions.lastserver.InitialServerChooser;
import com.mrgregles.bsp_velocity_additions.lastserver.JsonLastServerStore;
import com.mrgregles.bsp_velocity_additions.lastserver.LastServerFeature;
import com.mrgregles.bsp_velocity_additions.lastserver.LastServerStore;
import com.mrgregles.bsp_velocity_additions.lastserver.ServerReachability;
import com.mrgregles.bsp_velocity_additions.lastserver.VelocityRememberServerImport;
import com.velocitypowered.api.event.Subscribe;
import com.velocitypowered.api.event.proxy.ProxyInitializeEvent;
import com.velocitypowered.api.event.proxy.ProxyShutdownEvent;
import com.velocitypowered.api.plugin.Dependency;
import com.velocitypowered.api.plugin.Plugin;
import com.velocitypowered.api.plugin.annotation.DataDirectory;
import com.velocitypowered.api.proxy.ProxyServer;
import com.velocitypowered.api.proxy.server.RegisteredServer;
import org.slf4j.Logger;

import java.nio.file.Path;
import java.time.Duration;
import java.util.Optional;

/**
 * Entry point of BSP-Velocity Additions, a plugin for the Velocity proxy of the Build Secure Protect network.
 * Features arrive one task at a time; see docs/ and the vault section "BSP-Velocity Additions".
 */
@Plugin(
        id = BspVelocityAdditions.ID,
        name = "BSP-Velocity Additions",
        version = BuildConstants.VERSION,
        description = "Last server, shared chat, network tab list and join/leave messages for the BSP proxy.",
        url = "https://github.com/Build-Secure-Protect",
        authors = {"Mrgregles"},
        dependencies = {@Dependency(id = "luckperms", optional = true)}
)
public final class BspVelocityAdditions {

    public static final String ID = "bsp_velocity_additions";

    private final ProxyServer proxy;
    private final Logger logger;
    private final Path dataDirectory;

    private PluginConfig config = PluginConfig.defaults();
    private Messages messages = Messages.defaults();
    private LastServerStore lastServers;

    @Inject
    public BspVelocityAdditions(ProxyServer proxy, Logger logger, @DataDirectory Path dataDirectory) {
        this.proxy = proxy;
        this.logger = logger;
        this.dataDirectory = dataDirectory;
    }

    @Subscribe
    public void onProxyInitialize(ProxyInitializeEvent event) {
        config = PluginConfig.load(dataDirectory, logger);
        messages = Messages.load(dataDirectory, logger);
        logger.info("BSP-Velocity Additions {} starting; {} backend server(s) registered; data in {}",
                BuildConstants.VERSION, proxy.getAllServers().size(), dataDirectory);
        if (config.lastServer().enabled()) {
            startLastServer(config.lastServer());
        } else {
            logger.info("lastServer.enabled is false; players start where velocity.toml sends them");
        }
        if (config.joinLeave().enabled()) {
            proxy.getEventManager().register(this, new JoinLeaveFeature(proxy, config.joinLeave(), messages, logger));
            logger.info("Join and leave lines: on, silent permission {}",
                    config.joinLeave().hasSilentPermission() ? config.joinLeave().silentPermission() : "none");
        } else {
            logger.info("joinLeave.enabled is false; no network join or leave lines");
        }
        if (config.chat().enabled()) {
            proxy.getChannelRegistrar().register(ChatRelayFeature.IDENTIFIER);
            proxy.getEventManager().register(this, new ChatRelayFeature(proxy, config.chat(), config.players(), messages, logger));
            logger.info("Shared chat: on, listening on {} (needs proxy.relayChat = true in BSP-Core on every backend)",
                    ChatRelayFeature.CHANNEL);
        } else {
            logger.info("chat.enabled is false; no shared chat");
        }
        if (config.tabList().enabled() || config.players().enabled()) {
            PrefixProvider prefixes = LuckPermsPrefixes.detect(logger);
            NameFormatter names = new NameFormatter(config.tabList(), messages, prefixes);
            if (config.tabList().enabled()) {
                TabListFeature tabList = new TabListFeature(proxy, messages, names);
                proxy.getEventManager().register(this, tabList);
                proxy.getScheduler().buildTask(this, tabList::refreshAll)
                        .repeat(Duration.ofSeconds(config.tabList().refreshSeconds()))
                        .schedule();
                logger.info("Network tab list: on, refreshing every {} s", config.tabList().refreshSeconds());
            }
            if (config.players().enabled()) {
                CommandMeta meta = proxy.getCommandManager().metaBuilder("players").aliases("online").plugin(this).build();
                proxy.getCommandManager().register(meta, new PlayersCommand(proxy, config.players(), messages, names));
                logger.info("/players: on, server breakdown for {}", config.players().hasServersPermission()
                        ? config.players().serversPermission() : "the console only");
            }
        }
    }

    private void startLastServer(PluginConfig.LastServer settings) {
        JsonLastServerStore store = new JsonLastServerStore(dataDirectory.resolve(JsonLastServerStore.FILE_NAME),
                task -> proxy.getScheduler().buildTask(this, task).schedule(), logger);
        lastServers = store;
        if (settings.importVelocityRememberServer()) {
            Path pluginsFolder = dataDirectory.toAbsolutePath().getParent();
            if (pluginsFolder != null) {
                VelocityRememberServerImport.importInto(store, pluginsFolder.resolve(VelocityRememberServerImport.RELATIVE_PATH), logger);
            }
        }
        ServerReachability reachability = new ServerReachability(logger,
                task -> proxy.getScheduler().buildTask(this, task).schedule(), proxy);
        InitialServerChooser chooser = new InitialServerChooser(settings, this::server, reachability::isReachable,
                () -> proxy.getConfiguration().getAttemptConnectionOrder());
        proxy.getEventManager().register(this, new LastServerFeature(settings, store, reachability, chooser, logger));
        proxy.getScheduler().buildTask(this, () -> reachability.refresh(proxy))
                .repeat(Duration.ofSeconds(settings.pingSeconds()))
                .schedule();
        logger.info("Last server: on, {} player(s) remembered, pinging servers every {} s, fallback {}",
                store.size(), settings.pingSeconds(),
                settings.hasFallbackServer() ? settings.fallbackServer() : "the first reachable 'try' server");
    }

    @Subscribe
    public void onProxyShutdown(ProxyShutdownEvent event) {
        if (lastServers != null) {
            lastServers.flush();
        }
        logger.info("BSP-Velocity Additions stopped");
    }

    private Optional<RegisteredServer> server(String name) {
        return proxy.getServer(name);
    }

    public ProxyServer proxy() {
        return proxy;
    }

    public Path dataDirectory() {
        return dataDirectory;
    }

    public PluginConfig config() {
        return config;
    }

    public Messages messages() {
        return messages;
    }
}
