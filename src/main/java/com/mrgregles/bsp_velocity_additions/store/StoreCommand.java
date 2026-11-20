package com.mrgregles.bsp_velocity_additions.store;

import com.mrgregles.bsp_velocity_additions.config.Messages;
import com.mrgregles.bsp_velocity_additions.store.ProfileLookup.Profile;
import com.mrgregles.bsp_velocity_additions.store.ShopStore.OrderRow;
import com.mrgregles.bsp_velocity_additions.store.ShopStore.RewardRow;
import com.velocitypowered.api.command.CommandSource;
import com.velocitypowered.api.command.SimpleCommand;
import com.velocitypowered.api.proxy.Player;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import org.slf4j.Logger;

import java.sql.SQLException;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;

/**
 * {@code /store}, {@code /store link <email>}, {@code /store claim <order> <email>} and {@code /store status}. The
 * database work runs on the store's worker, never on Velocity's command thread. A failed link or claim never says which
 * part did not match, and counts towards the player's {@link FailedTries}.
 */
public final class StoreCommand implements SimpleCommand {

    static final String P = "store.";

    private final StoreService service;
    private final Executor worker;
    private final Messages messages;
    private final Supplier<StoreConfig> config;
    private final FailedTries tries;
    private final BooleanSupplier ready;
    private final Logger logger;

    public StoreCommand(StoreService service, Executor worker, Messages messages, Supplier<StoreConfig> config, FailedTries tries,
                        BooleanSupplier ready, Logger logger) {
        this.service = service;
        this.worker = worker;
        this.messages = messages;
        this.config = config;
        this.tries = tries;
        this.ready = ready;
        this.logger = logger;
    }

    @Override
    public void execute(Invocation invocation) {
        CommandSource source = invocation.source();
        String[] args = invocation.arguments();
        String sub = args.length == 0 ? "" : args[0].toLowerCase(Locale.ROOT);
        if (sub.isEmpty() || sub.equals("help")) {
            String url = config.get().fourthwall().shopUrl();
            Component link = messages.render(P + "shopLinkText").clickEvent(ClickEvent.openUrl(url));
            source.sendMessage(messages.render(P + "help", Placeholder.component("shop_link", link),
                    Placeholder.unparsed("shop_url", url)));
            return;
        }
        if (!(source instanceof Player player)) {
            source.sendMessage(messages.render(P + "playersOnly"));
            return;
        }
        Profile me;
        try {
            me = new Profile(player.getUniqueId(), player.getUsername());
        } catch (IllegalArgumentException e) {
            source.sendMessage(messages.render(P + "playersOnly"));
            return;
        }
        if (!ready.getAsBoolean()) {
            source.sendMessage(messages.render(P + "unavailable"));
            return;
        }
        switch (sub) {
            case "link" -> {
                if (args.length != 2) {
                    source.sendMessage(messages.render(P + "usage"));
                    return;
                }
                if (!tries.mayTry(me.uuid())) {
                    source.sendMessage(messages.render(P + "tooManyTries"));
                    return;
                }
                run(source, () -> {
                    if (tries.mayTry(me.uuid())) { // B13: checked again here, after any tries queued before this one
                        link(source, me, args[1]);
                    } else {
                        source.sendMessage(messages.render(P + "tooManyTries"));
                    }
                });
            }
            case "claim" -> {
                if (args.length != 3) {
                    source.sendMessage(messages.render(P + "usage"));
                    return;
                }
                if (!tries.mayTry(me.uuid())) {
                    source.sendMessage(messages.render(P + "tooManyTries"));
                    return;
                }
                run(source, () -> {
                    if (tries.mayTry(me.uuid())) {
                        claim(source, me, args[1], args[2]);
                    } else {
                        source.sendMessage(messages.render(P + "tooManyTries"));
                    }
                });
            }
            case "status" -> run(source, () -> status(source, me));
            default -> source.sendMessage(messages.render(P + "usage"));
        }
    }

    @FunctionalInterface
    interface Job {
        void run() throws SQLException;
    }

    private void run(CommandSource source, Job job) {
        try {
            worker.execute(() -> {
                try {
                    job.run();
                } catch (SQLException | RuntimeException e) {
                    logger.warn("Store: a /store command failed: {}", ShopDatabase.describe(e));
                    source.sendMessage(messages.render(P + "unavailable"));
                }
            });
        } catch (RuntimeException e) {
            source.sendMessage(messages.render(P + "unavailable"));
        }
    }

    private void link(CommandSource source, Profile me, String email) throws SQLException {
        StoreService.LinkResult r = service.link(me, email);
        switch (r.outcome()) {
            case LINKED -> source.sendMessage(messages.render(P + "linked", Placeholder.unparsed("email", Emails.mask(r.email())),
                    Placeholder.unparsed("orders", Integer.toString(r.orders())),
                    Placeholder.unparsed("memberships", Integer.toString(r.memberships()))));
            case ALREADY_LINKED -> source.sendMessage(messages.render(P + "alreadyLinked",
                    Placeholder.unparsed("email", Emails.mask(r.email()))));
            case FAILED -> {
                tries.fail(me.uuid());
                source.sendMessage(messages.render(P + "linkFailed"));
            }
        }
    }

    private void claim(CommandSource source, Profile me, String order, String email) throws SQLException {
        StoreService.ClaimResult r = service.claim(me, order, email);
        String key = switch (r.outcome()) {
            case CLAIMED -> "claimed";
            case ALREADY_CLAIMED -> "alreadyClaimed";
            case CANCELLED -> "claimCancelled";
            case UNAVAILABLE -> "unavailable";
            case FAILED -> "claimFailed";
        };
        if (r.outcome() == StoreService.ClaimOutcome.FAILED) {
            tries.fail(me.uuid());
        }
        source.sendMessage(messages.render(P + key, Placeholder.unparsed("order", r.order())));
    }

    private void status(CommandSource source, Profile me) throws SQLException {
        StoreService.Status s = service.status(me.uuid());
        if (s.orders().isEmpty() && s.rewards().isEmpty()) {
            source.sendMessage(messages.render(P + "statusNone"));
            return;
        }
        source.sendMessage(messages.render(P + "statusHeader"));
        for (OrderRow o : s.orders()) {
            source.sendMessage(messages.render(P + "statusOrder", Placeholder.unparsed("order", o.friendlyId()),
                    Placeholder.unparsed("state", words(o.state()))));
        }
        for (RewardRow r : s.rewards()) {
            source.sendMessage(messages.render(P + "statusReward", Placeholder.unparsed("reward", r.displayName()),
                    Placeholder.unparsed("action", r.action().equals("REVOKE") ? " (removal)" : ""),
                    Placeholder.unparsed("status", words(r.status()))));
        }
    }

    /** {@code AWAITING_PLAYER} to {@code awaiting player}. */
    static String words(String state) {
        return state == null ? "" : state.toLowerCase(Locale.ROOT).replace('_', ' ');
    }

    @Override
    public CompletableFuture<List<String>> suggestAsync(Invocation invocation) {
        String[] args = invocation.arguments();
        if (args.length <= 1) {
            String typed = args.length == 0 ? "" : args[0].toLowerCase(Locale.ROOT);
            return CompletableFuture.completedFuture(List.of("link", "claim", "status").stream()
                    .filter(s -> s.startsWith(typed)).toList());
        }
        return CompletableFuture.completedFuture(List.of());
    }

    @Override
    public boolean hasPermission(Invocation invocation) {
        return true;
    }
}
