package com.mrgregles.bsp_velocity_additions.store;

import com.mrgregles.bsp_velocity_additions.config.Messages;
import com.mrgregles.bsp_velocity_additions.store.ProfileLookup.Profile;
import com.mrgregles.bsp_velocity_additions.store.RewardsConfig.Reward;
import com.mrgregles.bsp_velocity_additions.store.ShopStore.LinkRow;
import com.mrgregles.bsp_velocity_additions.store.ShopStore.MemberRow;
import com.mrgregles.bsp_velocity_additions.store.ShopStore.OrderRow;
import com.mrgregles.bsp_velocity_additions.store.ShopStore.RewardRow;
import com.velocitypowered.api.command.CommandSource;
import com.velocitypowered.api.command.SimpleCommand;
import com.velocitypowered.api.proxy.ConsoleCommandSource;
import com.velocitypowered.api.proxy.Player;
import com.velocitypowered.api.proxy.ProxyServer;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import org.slf4j.Logger;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.function.BooleanSupplier;

/**
 * {@code /storeadmin lookup|assign|unlink|grant|resync|reload}, for the console and players with
 * {@link #PERMISSION} (answered by LuckPerms-Velocity). Emails are always shown masked. Database work runs on the
 * store's worker.
 */
public final class StoreAdminCommand implements SimpleCommand {

    public static final String PERMISSION = "bsp.shop.admin";
    static final String P = "storeAdmin.";

    /** Resync and reload, which belong to the feature. Both are called on the worker. */
    public interface Controls {
        Reconciler.Summary resync() throws Exception;

        RewardsConfig reload() throws Exception;
    }

    private final ProxyServer proxy;
    private final StoreService service;
    private final ProfileLookup profiles;
    private final Controls controls;
    private final Executor worker;
    private final Messages messages;
    private final BooleanSupplier ready;
    private final Logger logger;

    public StoreAdminCommand(ProxyServer proxy, StoreService service, ProfileLookup profiles, Controls controls, Executor worker,
                             Messages messages, BooleanSupplier ready, Logger logger) {
        this.proxy = proxy;
        this.service = service;
        this.profiles = profiles;
        this.controls = controls;
        this.worker = worker;
        this.messages = messages;
        this.ready = ready;
        this.logger = logger;
    }

    @Override
    public boolean hasPermission(Invocation invocation) {
        CommandSource s = invocation.source();
        return s instanceof ConsoleCommandSource || s.hasPermission(PERMISSION);
    }

    @Override
    public void execute(Invocation invocation) {
        CommandSource source = invocation.source();
        String[] a = invocation.arguments();
        String sub = a.length == 0 ? "" : a[0].toLowerCase(Locale.ROOT);
        boolean shapeOk = switch (sub) {
            case "lookup", "unlink", "revoke" -> a.length == 2;
            case "assign", "grant" -> a.length == 3;
            case "resync", "reload" -> a.length == 1;
            default -> false;
        };
        if (!shapeOk) {
            source.sendMessage(messages.render(P + "usage"));
            return;
        }
        if (!ready.getAsBoolean() && !sub.equals("reload")) {
            source.sendMessage(messages.render(StoreCommand.P + "unavailable"));
            return;
        }
        String staff = source instanceof Player p ? p.getUsername() : "console";
        if (sub.equals("resync")) {
            source.sendMessage(messages.render(P + "resyncStarted"));
        }
        try {
            worker.execute(() -> {
                try {
                    switch (sub) {
                        case "lookup" -> lookup(source, a[1]);
                        case "assign" -> assign(source, a[1], a[2]);
                        case "unlink" -> unlink(source, a[1]);
                        case "revoke" -> revoke(source, a[1]);
                        case "grant" -> grant(source, staff, a[1], a[2]);
                        case "resync" -> resync(source);
                        case "reload" -> reload(source);
                        default -> source.sendMessage(messages.render(P + "usage"));
                    }
                } catch (Exception e) {
                    logger.warn("Store: /storeadmin {} failed: {}", sub, ShopDatabase.describe(e));
                    source.sendMessage(messages.render(StoreCommand.P + "unavailable"));
                }
            });
        } catch (RuntimeException e) {
            source.sendMessage(messages.render(StoreCommand.P + "unavailable"));
        }
    }

    /** An online player first, else Mojang. */
    private Optional<Profile> player(String name) throws ProfileLookup.Unavailable {
        Optional<Player> online = proxy.getPlayer(name);
        if (online.isPresent()) {
            try {
                return Optional.of(new Profile(online.get().getUniqueId(), online.get().getUsername()));
            } catch (IllegalArgumentException e) {
                return Optional.empty();
            }
        }
        return profiles.find(name);
    }

    private void lookup(CommandSource source, String query) throws Exception {
        StoreService.Lookup found = service.lookup(query);
        if (found.isEmpty()) {
            source.sendMessage(messages.render(P + "lookupNothing", Placeholder.unparsed("query", query)));
            return;
        }
        for (LinkRow l : found.links()) {
            source.sendMessage(messages.render(P + "lookupLink", Placeholder.unparsed("player", l.playerName()),
                    Placeholder.unparsed("email", Emails.mask(l.email())), Placeholder.unparsed("by", l.linkedBy())));
        }
        for (OrderRow o : found.orders()) {
            source.sendMessage(messages.render(P + "lookupOrder", Placeholder.unparsed("order", o.friendlyId()),
                    Placeholder.unparsed("state", StoreCommand.words(o.state())),
                    Placeholder.unparsed("test", o.testMode() ? " (test)" : ""),
                    Placeholder.unparsed("player", o.playerName() == null ? "no player" : o.playerName()),
                    Placeholder.unparsed("email", Emails.mask(o.email()))));
        }
        for (MemberRow m : found.members()) {
            source.sendMessage(messages.render(P + "lookupMember", Placeholder.unparsed("member", m.memberId()),
                    Placeholder.unparsed("type", StoreCommand.words(m.subType())),
                    Placeholder.unparsed("tier", m.tierId() == null ? "none" : m.tierId()),
                    Placeholder.unparsed("granted", m.grantedTier() == null ? "none" : m.grantedTier()),
                    Placeholder.unparsed("email", Emails.mask(m.email()))));
        }
        for (RewardRow r : found.rewards()) {
            source.sendMessage(messages.render(P + "lookupReward", Placeholder.unparsed("id", Long.toString(r.id())),
                    Placeholder.unparsed("reward", r.rewardKey()), Placeholder.unparsed("action", r.action().toLowerCase(Locale.ROOT)),
                    Placeholder.unparsed("status", StoreCommand.words(r.status())), Placeholder.unparsed("target", r.target()),
                    Placeholder.unparsed("player", r.playerName()),
                    Placeholder.unparsed("claimed", r.claimedBy() == null ? "" : " by " + r.claimedBy())));
        }
    }

    private void assign(CommandSource source, String order, String name) throws Exception {
        Optional<Profile> p = player(name);
        if (p.isEmpty()) {
            source.sendMessage(messages.render(P + "unknownPlayer", Placeholder.unparsed("player", name)));
            return;
        }
        StoreService.ClaimResult r = service.staffAssign(order, p.get());
        String key = switch (r.outcome()) {
            case CLAIMED -> "assigned";
            case ALREADY_CLAIMED, CANCELLED -> "assignNotWaiting";
            case UNAVAILABLE -> "assignUnavailable";
            case FAILED -> "assignFailed";
        };
        source.sendMessage(messages.render(P + key, Placeholder.unparsed("order", r.order()),
                Placeholder.unparsed("player", p.get().name()),
                Placeholder.unparsed("state", r.outcome() == StoreService.ClaimOutcome.CANCELLED ? "cancelled" : "already assigned")));
    }

    private void unlink(CommandSource source, String name) throws Exception {
        Optional<StoreService.UnlinkResult> r = service.unlink(name);
        if (r.isEmpty()) {
            source.sendMessage(messages.render(P + "notLinked", Placeholder.unparsed("player", name)));
            return;
        }
        source.sendMessage(messages.render(P + "unlinked", Placeholder.unparsed("player", r.get().link().playerName()),
                Placeholder.unparsed("email", Emails.mask(r.get().link().email())),
                Placeholder.unparsed("revoked", Integer.toString(r.get().revoked()))));
    }

    private void revoke(CommandSource source, String order) throws Exception {
        StoreService.RevokeResult r = service.staffRevoke(order);
        switch (r.outcome()) {
            case NOT_FOUND -> source.sendMessage(messages.render(P + "assignFailed", Placeholder.unparsed("order", r.order())));
            case UNAVAILABLE -> source.sendMessage(messages.render(P + "assignUnavailable", Placeholder.unparsed("order", r.order())));
            case DONE -> {
                StoreService.Cancelled c = r.cancelled();
                source.sendMessage(messages.render(P + "revoked", Placeholder.unparsed("order", r.order()),
                        Placeholder.unparsed("cancelled", Integer.toString(c.cancelled())),
                        Placeholder.unparsed("revoked", Integer.toString(c.revoked())),
                        Placeholder.unparsed("waiting", c.waiting() == 0 ? ""
                                : "; " + c.waiting() + " being delivered right now, so run this again in a minute"),
                        Placeholder.unparsed("lost", c.lost().isEmpty() ? ""
                                : "; " + c.lost().size() + " given that cannot be taken back automatically (see the warning)")));
            }
        }
    }

    private void grant(CommandSource source, String staff, String name, String rewardKey) throws Exception {
        Optional<Profile> p = player(name);
        if (p.isEmpty()) {
            source.sendMessage(messages.render(P + "unknownPlayer", Placeholder.unparsed("player", name)));
            return;
        }
        Optional<Reward> r = service.grant(staff, p.get(), rewardKey);
        if (r.isEmpty()) {
            source.sendMessage(messages.render(P + "unknownReward", Placeholder.unparsed("reward", rewardKey)));
            return;
        }
        source.sendMessage(messages.render(P + "granted", Placeholder.unparsed("reward", r.get().displayName()),
                Placeholder.unparsed("player", p.get().name())));
    }

    private void resync(CommandSource source) {
        try {
            Reconciler.Summary s = controls.resync();
            source.sendMessage(messages.render(P + "resyncDone", Placeholder.unparsed("summary", s.line())));
        } catch (Exception e) {
            source.sendMessage(messages.render(P + "resyncFailed", Placeholder.unparsed("reason", String.valueOf(e.getMessage()))));
        }
    }

    private void reload(CommandSource source) {
        try {
            RewardsConfig r = controls.reload();
            source.sendMessage(messages.render(P + "reloaded", Placeholder.unparsed("products", Integer.toString(r.products().size())),
                    Placeholder.unparsed("memberships", Integer.toString(r.memberships().size())),
                    Placeholder.unparsed("problems", r.problems().isEmpty() ? "" : ", " + r.problems().size()
                            + " left out (see the console)")));
        } catch (Exception e) {
            source.sendMessage(messages.render(P + "reloadFailed", Placeholder.unparsed("reason", String.valueOf(e.getMessage()))));
        }
    }

    @Override
    public CompletableFuture<List<String>> suggestAsync(Invocation invocation) {
        String[] a = invocation.arguments();
        if (a.length <= 1) {
            String typed = a.length == 0 ? "" : a[0].toLowerCase(Locale.ROOT);
            return CompletableFuture.completedFuture(List.of("lookup", "assign", "unlink", "revoke", "grant", "resync", "reload").stream()
                    .filter(s -> s.startsWith(typed)).toList());
        }
        String sub = a[0].toLowerCase(Locale.ROOT);
        boolean nameHere = (a.length == 2 && (sub.equals("lookup") || sub.equals("unlink") || sub.equals("grant")))
                || (a.length == 3 && sub.equals("assign"));
        if (nameHere) {
            String typed = a[a.length - 1].toLowerCase(Locale.ROOT);
            List<String> names = new ArrayList<>();
            for (Player p : proxy.getAllPlayers()) {
                if (p.getUsername().toLowerCase(Locale.ROOT).startsWith(typed)) {
                    names.add(p.getUsername());
                }
            }
            return CompletableFuture.completedFuture(names);
        }
        return CompletableFuture.completedFuture(List.of());
    }
}
