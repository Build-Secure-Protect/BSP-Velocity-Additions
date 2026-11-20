package com.mrgregles.bsp_velocity_additions.store;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.mrgregles.bsp_velocity_additions.store.Fourthwall.BadPayload;
import com.mrgregles.bsp_velocity_additions.store.Fourthwall.Envelope;
import com.mrgregles.bsp_velocity_additions.store.Fourthwall.Line;
import com.mrgregles.bsp_velocity_additions.store.Fourthwall.Member;
import com.mrgregles.bsp_velocity_additions.store.Fourthwall.Order;
import com.mrgregles.bsp_velocity_additions.store.ProfileLookup.Profile;
import com.mrgregles.bsp_velocity_additions.store.RewardsConfig.Reward;
import com.mrgregles.bsp_velocity_additions.store.ShopStore.LinkRow;
import com.mrgregles.bsp_velocity_additions.store.ShopStore.MemberRow;
import com.mrgregles.bsp_velocity_additions.store.ShopStore.NewReward;
import com.mrgregles.bsp_velocity_additions.store.ShopStore.OrderRow;
import com.mrgregles.bsp_velocity_additions.store.ShopStore.RewardRow;
import org.slf4j.Logger;

import java.sql.Connection;
import java.sql.SQLException;
import java.time.Clock;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;

/**
 * The store's rules (SH-R1): what each Fourthwall event, claim, link and staff command does to {@code bsp_shop}. Every
 * method talks to the database and maybe Mojang or Fourthwall, so it is only ever called on the store's own worker
 * thread, never on an HTTP thread or one of Velocity's event threads. Each change is one transaction; reward rows are
 * deduplicated by their {@code dedupe_key}, so a replayed webhook or a reconciliation never writes a second row.
 */
public final class StoreService {

    public static final String AWAITING = "AWAITING_PLAYER";
    public static final String ASSIGNED = "ASSIGNED";
    public static final String CANCELLED = "CANCELLED";
    /** Most rows one order line may make; a larger quantity is capped with a staff warning. */
    static final int MAX_UNITS_PER_LINE = 64;

    /** Something to tell online staff (and the log). */
    @FunctionalInterface
    public interface StaffAlerts {
        void warn(String text);
    }

    /** The order as Fourthwall has it, from its API (reconciliation and a claim for an order no stored event holds). */
    @FunctionalInterface
    public interface OrderFetcher {
        Optional<Order> fetch(String orderId);
    }

    public enum Outcome { DONE, RETRY, FAILED, SKIPPED }

    /** What processing an event came to, and why when it is not DONE. */
    public record Result(Outcome outcome, String reason) {
        static final Result DONE = new Result(Outcome.DONE, null);

        static Result retry(String why) {
            return new Result(Outcome.RETRY, why);
        }
    }

    private final ShopDatabase db;
    private final ProfileLookup profiles;
    private final Supplier<RewardsConfig> rewards;
    private final Supplier<StoreConfig> config;
    private final OrderFetcher fetcher;
    private final StaffAlerts staff;
    private final Clock clock;
    private final Logger logger;
    /** Delivered rows staff have already been told about, so a re-check does not repeat the warning. */
    private final Set<Long> warnedRows = ConcurrentHashMap.newKeySet();

    public StoreService(ShopDatabase db, ProfileLookup profiles, Supplier<RewardsConfig> rewards, Supplier<StoreConfig> config,
                        OrderFetcher fetcher, StaffAlerts staff, Clock clock, Logger logger) {
        this.db = db;
        this.profiles = profiles;
        this.rewards = rewards;
        this.config = config;
        this.fetcher = fetcher;
        this.staff = staff;
        this.clock = clock;
        this.logger = logger;
    }

    // ================================================================ events

    /** Processes one stored event and records the outcome on its row. */
    public Result processEvent(String eventId) {
        ShopStore.EventRow ev;
        try {
            ev = db.call(c -> ShopStore.event(c, eventId)).orElse(null);
        } catch (SQLException e) {
            return Result.retry("database: " + ShopDatabase.describe(e));
        }
        if (ev == null) {
            return new Result(Outcome.FAILED, "no such event");
        }
        if (ev.processedAt() != null) {
            return Result.DONE;
        }
        if (ev.testMode() && !config.get().fourthwall().processTestEvents()) {
            note(eventId, "test event, not processed (processTestEvents = false)", false);
            return new Result(Outcome.SKIPPED, "test event");
        }
        Result result;
        try {
            Envelope env = Envelope.parse(ev.body());
            result = switch (env.type()) {
                case Fourthwall.ORDER_PLACED -> handleOrder(Order.parse(env.requireData()), env.testMode());
                case Fourthwall.ORDER_UPDATED -> handleOrderUpdated(env.requireData(), env.testMode());
                case Fourthwall.SUBSCRIPTION_PURCHASED, Fourthwall.SUBSCRIPTION_CHANGED, Fourthwall.SUBSCRIPTION_EXPIRED ->
                        applyMember(Member.parse(env.requireData()), env.type()).result();
                default -> {
                    note(eventId, "ignored: the store does not use " + env.type(), true);
                    yield null;
                }
            };
            if (result == null) {
                return Result.DONE;
            }
        } catch (BadPayload e) {
            // B11: it will never work, so it is done (with its error) and not tried again at every start
            logger.warn("Store: event {} cannot be used: {}", eventId, e.getMessage());
            note(eventId, "bad payload: " + e.getMessage(), true);
            return new Result(Outcome.FAILED, e.getMessage());
        } catch (ProfileLookup.Unavailable e) {
            note(eventId, "waiting for Mojang: " + e.getMessage(), false);
            return Result.retry("Mojang: " + e.getMessage());
        } catch (SQLException e) {
            note(eventId, "database: " + ShopDatabase.describe(e), false);
            return Result.retry("database: " + ShopDatabase.describe(e));
        } catch (RuntimeException e) {
            logger.error("Store: event {} failed", eventId, e);
            note(eventId, "failed: " + e, true);
            return new Result(Outcome.FAILED, e.toString());
        }
        if (result.outcome() == Outcome.DONE) {
            note(eventId, null, true);
        } else {
            note(eventId, result.reason(), false);
        }
        return result;
    }

    private void note(String eventId, String text, boolean processed) {
        try {
            db.call(c -> {
                if (processed) {
                    ShopStore.markProcessed(c, eventId, text);
                } else {
                    ShopStore.markError(c, eventId, text);
                }
                return null;
            });
        } catch (SQLException e) {
            logger.warn("Store: could not record the outcome of event {}: {}", eventId, ShopDatabase.describe(e));
        }
    }

    // ================================================================ orders

    /** ORDER_PLACED, and any order seen for the first time. */
    Result handleOrder(Order o, boolean test) throws SQLException, ProfileLookup.Unavailable {
        Optional<OrderRow> existing = db.call(c -> ShopStore.order(c, o.id(), false));
        if (o.cancelled()) {
            if (existing.isEmpty()) {
                db.transaction(c -> {
                    if (ShopStore.order(c, o.id(), true).isEmpty()) {
                        ShopStore.insertOrder(c, o, CANCELLED, null, test);
                    }
                    return null;
                });
                return Result.DONE;
            }
            return cancelOrder(o, test);
        }
        if (existing.isPresent() && !AWAITING.equals(existing.get().state())) {
            return Result.DONE; // assigned (its rows exist) or cancelled
        }
        Profile player = resolve(o);
        List<String> warnings = new ArrayList<>();
        String state = db.transaction(c -> {
            Optional<OrderRow> row = ShopStore.order(c, o.id(), true);
            if (row.isEmpty()) {
                ShopStore.insertOrder(c, o, player == null ? AWAITING : ASSIGNED, player, test);
            } else if (!AWAITING.equals(row.get().state())) {
                return row.get().state();
            } else if (player != null) {
                ShopStore.setOrderState(c, o.id(), ASSIGNED, player);
            }
            if (player != null) {
                warnings.addAll(writeOrderRows(c, o, player, row.map(OrderRow::testMode).orElse(test)));
                return ASSIGNED;
            }
            return AWAITING;
        });
        if (ASSIGNED.equals(state) && player != null) {
            logger.info("Store: order {} assigned to {}", o.friendlyId(), player.name());
        } else if (AWAITING.equals(state)) {
            logger.info("Store: order {} waits for a player ({}); /store claim or /store link assigns it", o.friendlyId(),
                    o.username() == null || o.username().isBlank() ? "no username given" : "the username is not a Java account");
        }
        warnings.forEach(staff::warn);
        return Result.DONE;
    }

    /** The buyer's Java account: the username typed at checkout, else the player linked to the order's email. */
    private Profile resolve(Order o) throws SQLException, ProfileLookup.Unavailable {
        String typed = o.username() == null ? "" : o.username().trim();
        if (Commands.NAME.matcher(typed).matches()) {
            Optional<Profile> found = profiles.find(typed);
            if (found.isPresent()) {
                return found.get();
            }
        }
        if (o.email().isEmpty()) {
            return null;
        }
        return db.call(c -> ShopStore.linkByEmail(c, o.email())).map(StoreService::profile).orElse(null);
    }

    private static Profile profile(LinkRow link) {
        return new Profile(link.player(), link.playerName());
    }

    /** ORDER_UPDATED: cancellation (D6), a new email, or anything else (stored only). */
    Result handleOrderUpdated(JsonObject data, boolean test) throws SQLException, ProfileLookup.Unavailable, BadPayload {
        JsonElement orderJson = data.get("order");
        if (orderJson == null || !orderJson.isJsonObject()) {
            throw new BadPayload("ORDER_UPDATED has no order");
        }
        Order o = Order.parse(orderJson.getAsJsonObject());
        String update = "";
        JsonElement u = data.get("update");
        if (u != null && u.isJsonObject()) {
            update = Objects.requireNonNullElse(Fourthwall.string(u.getAsJsonObject(), "type"), "");
        }
        Optional<OrderRow> existing = db.call(c -> ShopStore.order(c, o.id(), false));
        if (existing.isEmpty()) {
            return handleOrder(o, test); // its ORDER_PLACED has not arrived (or never will)
        }
        if (o.cancelled()) {
            return cancelOrder(o, test);
        }
        if (update.equals("EMAIL") && !o.email().isEmpty() && !o.email().equals(existing.get().email())) {
            db.call(c -> {
                ShopStore.setOrderEmail(c, o.id(), o.email());
                return null;
            });
            logger.info("Store: order {} has a new email ({})", o.friendlyId(), Emails.mask(o.email()));
            if (AWAITING.equals(existing.get().state())) {
                return handleOrder(o, test); // the new email may be linked
            }
        }
        return Result.DONE;
    }

    /**
     * The reward for an order line (D42): the variant's SKU when the rewards know it, otherwise the product's slug
     * (digital products on Fourthwall have no SKU).
     */
    static Optional<Reward> rewardFor(RewardsConfig config, Line line) {
        if (line.sku() != null && !line.sku().isBlank()) {
            Optional<Reward> bySku = config.product(line.sku());
            if (bySku.isPresent()) {
                return bySku;
            }
        }
        if (line.slug() != null && !line.slug().isBlank()) {
            return config.product(line.slug());
        }
        return Optional.empty();
    }

    private static String describe(Line line) {
        boolean sku = line.sku() != null && !line.sku().isBlank();
        boolean slug = line.slug() != null && !line.slug().isBlank();
        if (sku && slug) {
            return "SKU " + line.sku() + " (product " + line.slug() + ")";
        }
        return sku ? "SKU " + line.sku() : slug ? "product " + line.slug() : null;
    }

    /** One row per unit of every line with a reward; returns warnings for staff. */
    private List<String> writeOrderRows(Connection c, Order o, Profile p, boolean test) throws SQLException {
        RewardsConfig config = rewards.get();
        List<String> warnings = new ArrayList<>();
        int written = 0;
        for (Line line : o.lines()) {
            String what = describe(line);
            if (what == null) {
                warnings.add("Order " + o.friendlyId() + ": line " + (line.index() + 1) + " has neither a SKU nor a product slug, "
                        + "so it gives nothing in game.");
                continue;
            }
            Optional<Reward> found = rewardFor(config, line);
            if (found.isEmpty()) {
                warnings.add("Order " + o.friendlyId() + ": " + what + " is not in " + RewardsConfig.FILE_NAME
                        + ", so it gives nothing in game. /storeadmin grant can give it by hand.");
                continue;
            }
            Reward r = found.get();
            int quantity = Math.max(1, line.quantity());
            int units = r.perQuantity() ? quantity : 1;
            if (units > MAX_UNITS_PER_LINE) {
                warnings.add("Order " + o.friendlyId() + ": " + quantity + " x " + r.key() + " is more than " + MAX_UNITS_PER_LINE
                        + "; only " + MAX_UNITS_PER_LINE + " were written.");
                units = MAX_UNITS_PER_LINE;
            }
            for (int unit = 0; unit < units; unit++) {
                Commands.Values values = new Commands.Values(p.name(), p.uuid(), r.perQuantity() ? 1 : quantity, o.friendlyId(), r.key());
                NewReward row = new NewReward("ORDER:" + o.id() + ":" + r.key() + ":" + line.index() + ":" + unit + ":GRANT",
                        "ORDER", o.friendlyId(), r.key(), r.displayName(), "GRANT", p.uuid(), p.name(), r.target(),
                        r.requireOnline(), r.safeToRetry(), Commands.toJson(r.grant(), values), test);
                if (ShopStore.insertReward(c, row)) {
                    written++;
                }
            }
        }
        if (written > 0) {
            logger.info("Store: order {}: {} reward row(s) written for {}", o.friendlyId(), written, p.name());
        }
        return warnings;
    }

    /**
     * Cancels an order (D6): its PENDING rows become CANCELLED; delivered (or failed) rows get a REVOKE row where the
     * reward has revoke commands, and staff are told about the rest. A row a backend is delivering right now
     * (CLAIMED) is waited for: the result is RETRY until none is left.
     */
    Result cancelOrder(Order o, boolean test) throws SQLException {
        Cancelled done = cancel(o, test);
        if (done.waiting() > 0) {
            return Result.retry(done.waiting() + " row(s) of the cancelled order are being delivered; checking again");
        }
        return Result.DONE;
    }

    /** What a cancellation did: rows cancelled, take-backs written, rows being delivered now, things staff were told. */
    public record Cancelled(String order, int cancelled, int revoked, int waiting, List<String> lost) {
    }

    private Cancelled cancel(Order o, boolean test) throws SQLException {
        RewardsConfig config = rewards.get();
        List<String> lost = new ArrayList<>();
        int[] counts = new int[3]; // cancelled, revoked, waiting
        String friendly = db.transaction(c -> {
            Optional<OrderRow> row = ShopStore.order(c, o.id(), true);
            if (row.isEmpty()) {
                ShopStore.insertOrder(c, o, CANCELLED, null, test);
                return o.friendlyId();
            }
            if (!CANCELLED.equals(row.get().state())) {
                ShopStore.setOrderState(c, o.id(), CANCELLED, null);
            }
            // B15: the rows are read with a lock, so a status a backend changed a moment ago is seen as it is now
            for (RewardRow r : ShopStore.rewardsWithKeyPrefix(c, "ORDER:" + o.id() + ":", true)) {
                if (!"GRANT".equals(r.action())) {
                    continue;
                }
                String status = r.status();
                if ("PENDING".equals(status)) {
                    if (ShopStore.cancelPending(c, r.id())) {
                        counts[0]++;
                        continue;
                    }
                    status = ShopStore.rewardStatus(c, r.id()).orElse("CANCELLED");
                }
                if ("CANCELLED".equals(status)) {
                    continue;
                }
                if ("CLAIMED".equals(status)) {
                    counts[2]++;
                    continue;
                }
                Optional<Reward> reward = config.product(r.rewardKey());
                if (reward.isPresent() && reward.get().canRevoke()) {
                    Reward rw = reward.get();
                    int quantity = rw.perQuantity() ? 1 : quantityOf(o, r.dedupeKey());
                    Commands.Values values = new Commands.Values(r.playerName(), r.player(), quantity, row.get().friendlyId(), rw.key());
                    NewReward revoke = new NewReward(r.dedupeKey().substring(0, r.dedupeKey().length() - "GRANT".length()) + "REVOKE",
                            "ORDER", row.get().friendlyId(), rw.key(), rw.displayName(), "REVOKE", r.player(), r.playerName(),
                            rw.target(), rw.requireOnline(), rw.safeToRetry(), Commands.toJson(rw.revoke(), values), r.testMode());
                    if (ShopStore.insertReward(c, revoke)) {
                        counts[1]++;
                    }
                } else if (warnedRows.add(r.id())) {
                    lost.add(r.displayName() + " (row " + r.id() + ", " + status.toLowerCase(Locale.ROOT) + ") for " + r.playerName());
                }
            }
            return row.get().friendlyId();
        });
        logger.info("Store: order {} cancelled: {} pending row(s) cancelled, {} revoke row(s) written{}", friendly, counts[0],
                counts[1], counts[2] > 0 ? ", " + counts[2] + " being delivered now (checked again shortly)" : "");
        if (!lost.isEmpty()) {
            staff.warn("Order " + friendly + " was cancelled, but these were already given and cannot be taken back "
                    + "automatically: " + String.join("; ", lost) + ".");
        }
        return new Cancelled(friendly, counts[0], counts[1], counts[2], List.copyOf(lost));
    }

    public enum RevokeOutcome { DONE, NOT_FOUND, UNAVAILABLE }

    public record RevokeResult(RevokeOutcome outcome, String order, Cancelled cancelled) {
    }

    /**
     * {@code /storeadmin revoke <order>} (T27, D43): takes an order back by hand after a refund or chargeback, exactly as
     * a Fourthwall cancellation would. Fourthwall cannot cancel a delivered digital order, so this is the way for
     * ranks. Safe to run again: it writes no second take-back.
     */
    public RevokeResult staffRevoke(String orderRef) throws SQLException {
        Optional<OrderRow> row = findOrder(orderRef);
        if (row.isEmpty()) {
            return new RevokeResult(RevokeOutcome.NOT_FOUND, orderRef.trim(), null);
        }
        Optional<Order> data = orderData(row.get().orderId());
        if (data.isEmpty()) {
            logger.warn("Store: order {} cannot be taken back: its contents are in no stored event and Fourthwall's API "
                    + "did not give them", row.get().friendlyId());
            return new RevokeResult(RevokeOutcome.UNAVAILABLE, row.get().friendlyId(), null);
        }
        Cancelled done = cancel(data.get(), row.get().testMode());
        logger.info("Store: order {} taken back by staff", done.order());
        return new RevokeResult(RevokeOutcome.DONE, done.order(), done);
    }

    /** An order by its Fourthwall id or its order number. */
    private Optional<OrderRow> findOrder(String orderRef) throws SQLException {
        String ref = orderRef.trim();
        return db.call(c -> {
            Optional<OrderRow> byId = ShopStore.order(c, ref, false);
            if (byId.isPresent()) {
                return byId;
            }
            String friendly = Fourthwall.cleanOrderNumber(ref);
            return friendly.isEmpty() ? Optional.<OrderRow>empty() : ShopStore.ordersByFriendlyId(c, friendly).stream().findFirst();
        });
    }

    /** The line's quantity, from {@code ORDER:<order>:<sku>:<line>:<unit>:GRANT} (the SKU may hold colons). */
    private static int quantityOf(Order o, String dedupeKey) {
        String[] parts = dedupeKey.split(":");
        try {
            int index = Integer.parseInt(parts[parts.length - 3]);
            for (Line l : o.lines()) {
                if (l.index() == index) {
                    return Math.max(1, l.quantity());
                }
            }
        } catch (RuntimeException ignored) {
            // fall through
        }
        return 1;
    }

    // ================================================================ memberships

    public record MemberResult(Result result, boolean linked, boolean changed) {
    }

    /** SUBSCRIPTION_* events and reconciliation; {@code eventType} is null for reconciliation. */
    public MemberResult applyMember(Member m, String eventType) throws SQLException {
        List<String> warnings = new ArrayList<>();
        boolean[] flags = new boolean[2]; // linked, changed
        db.transaction(c -> {
            Optional<MemberRow> existing = ShopStore.member(c, m.id(), true);
            ShopStore.saveMember(c, m, existing.isPresent());
            if (m.email().isEmpty()) {
                return null;
            }
            Optional<LinkRow> link = ShopStore.linkByEmail(c, m.email());
            if (link.isEmpty()) {
                return null; // D40: stored; written when they /store link
            }
            flags[0] = true;
            String granted = existing.map(MemberRow::grantedTier).orElse(null);
            String desired = desiredTier(m.type(), m.tierId(), eventType);
            flags[1] = syncMember(c, m.id(), granted, desired, profile(link.get()), warnings);
            return null;
        });
        warnings.forEach(staff::warn);
        return new MemberResult(Result.DONE, flags[0], flags[1]);
    }

    /** The tier whose rewards should be applied now, or null for none. */
    String desiredTier(String type, String tierId, String eventType) {
        if (Fourthwall.SUBSCRIPTION_EXPIRED.equals(eventType) || tierId == null) {
            return null;
        }
        if ("ACTIVE".equals(type)) {
            return tierId;
        }
        return config.get().membership().revokeOn().contains(type) ? null : tierId;
    }

    /**
     * Makes {@code granted_tier} equal {@code desired}: a REVOKE row for the old tier, a GRANT row for the new one, in
     * the caller's transaction (which holds the member's row lock). True when anything changed.
     */
    private boolean syncMember(Connection c, String memberId, String granted, String desired, Profile p, List<String> warnings)
            throws SQLException {
        if (Objects.equals(granted, desired)) {
            return false;
        }
        RewardsConfig config = rewards.get();
        int seq = ShopStore.countMembershipRows(c, memberId) + 1;
        if (granted != null) {
            Optional<Reward> old = config.membership(granted);
            if (old.isPresent() && old.get().canRevoke()) {
                Reward r = old.get();
                ShopStore.insertReward(c, membershipRow(memberId, seq++, r, "REVOKE", r.revoke(), p));
            } else {
                warnings.add("Membership tier " + granted + " of " + p.name() + " ended, but " + RewardsConfig.FILE_NAME
                        + " has no revoke commands for it; take its rewards away by hand.");
            }
        }
        String now = null;
        if (desired != null) {
            Optional<Reward> next = config.membership(desired);
            if (next.isPresent()) {
                Reward r = next.get();
                ShopStore.insertReward(c, membershipRow(memberId, seq, r, "GRANT", r.grant(), p));
                now = desired;
            } else {
                warnings.add("Membership tier " + desired + " of " + p.name() + " is not in " + RewardsConfig.FILE_NAME
                        + ", so it gives nothing in game until it is added (the next resync then grants it).");
            }
        }
        ShopStore.setGrantedTier(c, memberId, now);
        logger.info("Store: membership {} of {}: rewards now {} (were {})", memberId, p.name(), now == null ? "none" : now,
                granted == null ? "none" : granted);
        return true;
    }

    private static NewReward membershipRow(String memberId, int seq, Reward r, String action, List<String> templates, Profile p) {
        Commands.Values values = new Commands.Values(p.name(), p.uuid(), 1, memberId, r.key());
        return new NewReward("MEMBERSHIP:" + memberId + ":" + seq + ":" + r.key() + ":" + action, "MEMBERSHIP", memberId, r.key(),
                r.displayName(), action, p.uuid(), p.name(), r.target(), r.requireOnline(), r.safeToRetry(),
                Commands.toJson(templates, values), false);
    }

    // ================================================================ players

    public enum LinkOutcome { LINKED, ALREADY_LINKED, FAILED }

    public record LinkResult(LinkOutcome outcome, String email, int orders, int memberships) {
    }

    /**
     * {@code /store link <email>}: only an email that some order or membership was made with, and that no other
     * player has linked. Then every waiting order from it is assigned and its memberships' rewards are written.
     */
    public LinkResult link(Profile p, String rawEmail) throws SQLException {
        String email = Emails.normalize(rawEmail);
        if (!Emails.looksValid(email)) {
            return new LinkResult(LinkOutcome.FAILED, email, 0, 0);
        }
        Map<String, Order> waiting = waitingOrders(email);
        List<String> warnings = new ArrayList<>();
        LinkResult result;
        try {
            result = db.transaction(c -> {
                Optional<LinkRow> mine = ShopStore.linkByPlayer(c, p.uuid());
                if (mine.isPresent()) {
                    return new LinkResult(LinkOutcome.ALREADY_LINKED, mine.get().email(), 0, 0);
                }
                if (ShopStore.linkByEmail(c, email).isPresent()) {
                    return new LinkResult(LinkOutcome.FAILED, email, 0, 0);
                }
                List<OrderRow> orders = ShopStore.ordersByEmail(c, email);
                List<MemberRow> members = ShopStore.membersByEmail(c, email);
                if (orders.isEmpty() && members.isEmpty()) {
                    return new LinkResult(LinkOutcome.FAILED, email, 0, 0);
                }
                ShopStore.insertLink(c, p, email, "player");
                int assigned = 0;
                for (Order o : waiting.values()) {
                    if (assignInTransaction(c, o, p, warnings)) {
                        assigned++;
                    }
                }
                int memberships = 0;
                for (MemberRow m : members) {
                    MemberRow locked = ShopStore.member(c, m.memberId(), true).orElse(m);
                    if (syncMember(c, locked.memberId(), locked.grantedTier(),
                            desiredTier(locked.subType(), locked.tierId(), null), p, warnings)) {
                        memberships++;
                    }
                }
                return new LinkResult(LinkOutcome.LINKED, email, assigned, memberships);
            });
        } catch (SQLException e) {
            if (ShopStore.isDuplicate(e)) {
                return new LinkResult(LinkOutcome.FAILED, email, 0, 0); // linked by someone else a moment ago
            }
            throw e;
        }
        if (result.outcome() == LinkOutcome.LINKED) {
            logger.info("Store: {} linked {} ({} order(s) assigned, {} membership(s) granted)", p.name(), Emails.mask(email),
                    result.orders(), result.memberships());
        }
        warnings.forEach(staff::warn);
        return result;
    }

    /** The data of every AWAITING_PLAYER order from this email, read before the transaction (it may ask Fourthwall). */
    private Map<String, Order> waitingOrders(String email) throws SQLException {
        Map<String, Order> out = new LinkedHashMap<>();
        for (OrderRow row : db.call(c -> ShopStore.ordersByEmail(c, email))) {
            if (AWAITING.equals(row.state())) {
                orderData(row.orderId()).ifPresentOrElse(o -> out.put(o.id(), o),
                        () -> logger.warn("Store: order {} waits for a player but its contents cannot be found; "
                                + "/storeadmin resync may fetch them", row.friendlyId()));
            }
        }
        return out;
    }

    /** Assigns a waiting order and writes its rows; false when it is no longer waiting. */
    private boolean assignInTransaction(Connection c, Order o, Profile p, List<String> warnings) throws SQLException {
        Optional<OrderRow> row = ShopStore.order(c, o.id(), true);
        if (row.isEmpty() || !AWAITING.equals(row.get().state())) {
            return false;
        }
        ShopStore.setOrderState(c, o.id(), ASSIGNED, p);
        warnings.addAll(writeOrderRows(c, o, p, row.get().testMode()));
        return true;
    }

    /** The order's contents: from the newest stored event that holds it, else from Fourthwall's API. */
    Optional<Order> orderData(String orderId) throws SQLException {
        for (String body : db.call(c -> ShopStore.eventBodiesMentioning(c, orderId))) {
            try {
                Envelope env = Envelope.parse(body);
                JsonObject data = env.requireData();
                JsonElement order = Fourthwall.ORDER_UPDATED.equals(env.type()) ? data.get("order") : data;
                if (order != null && order.isJsonObject()) {
                    Order o = Order.parse(order.getAsJsonObject());
                    if (o.id().equals(orderId)) {
                        return Optional.of(o);
                    }
                }
            } catch (BadPayload | RuntimeException ignored) {
                // not this one
            }
        }
        return fetcher.fetch(orderId);
    }

    public enum ClaimOutcome { CLAIMED, ALREADY_CLAIMED, CANCELLED, FAILED, UNAVAILABLE }

    public record ClaimResult(ClaimOutcome outcome, String order) {
    }

    /** {@code /store claim <order number> <email>}: both must match one waiting order. */
    public ClaimResult claim(Profile p, String rawOrder, String rawEmail) throws SQLException {
        String friendly = Fourthwall.cleanOrderNumber(rawOrder);
        String email = Emails.normalize(rawEmail);
        if (!Commands.FRIENDLY_ID.matcher(friendly).matches() || !Emails.looksValid(email)) {
            return new ClaimResult(ClaimOutcome.FAILED, friendly);
        }
        String f = friendly;
        Optional<OrderRow> match = db.call(c -> ShopStore.ordersByFriendlyId(c, f)).stream()
                .filter(r -> r.email().equals(email)).findFirst();
        if (match.isEmpty()) {
            return new ClaimResult(ClaimOutcome.FAILED, friendly);
        }
        return assign(match.get(), p, "claimed by");
    }

    private ClaimResult assign(OrderRow row, Profile p, String how) throws SQLException {
        if (CANCELLED.equals(row.state())) {
            return new ClaimResult(ClaimOutcome.CANCELLED, row.friendlyId());
        }
        if (!AWAITING.equals(row.state())) {
            return new ClaimResult(ClaimOutcome.ALREADY_CLAIMED, row.friendlyId());
        }
        Optional<Order> data = orderData(row.orderId());
        if (data.isEmpty()) {
            logger.warn("Store: order {} cannot be assigned: its contents are in no stored event and Fourthwall's API "
                    + "did not give them", row.friendlyId());
            return new ClaimResult(ClaimOutcome.UNAVAILABLE, row.friendlyId());
        }
        List<String> warnings = new ArrayList<>();
        boolean done = db.transaction(c -> assignInTransaction(c, data.get(), p, warnings));
        warnings.forEach(staff::warn);
        if (!done) {
            return new ClaimResult(ClaimOutcome.ALREADY_CLAIMED, row.friendlyId());
        }
        logger.info("Store: order {} {} {}", row.friendlyId(), how, p.name());
        return new ClaimResult(ClaimOutcome.CLAIMED, row.friendlyId());
    }

    public record Status(List<OrderRow> orders, List<RewardRow> rewards) {
    }

    /** {@code /store status}: the player's orders and their latest reward rows. */
    public Status status(UUID player) throws SQLException {
        return db.call(c -> new Status(ShopStore.ordersByPlayer(c, player), ShopStore.rewardsForPlayer(c, player, 10)));
    }

    // ================================================================ staff

    /** {@code /storeadmin assign <order> <player>}: order number or Fourthwall id. */
    public ClaimResult staffAssign(String orderRef, Profile p) throws SQLException {
        Optional<OrderRow> row = findOrder(orderRef);
        if (row.isEmpty()) {
            return new ClaimResult(ClaimOutcome.FAILED, orderRef.trim());
        }
        return assign(row.get(), p, "assigned by staff to");
    }

    public record UnlinkResult(LinkRow link, int revoked) {
    }

    /**
     * {@code /storeadmin unlink <player>}: removes the link. Membership rewards applied through it are taken back
     * (REVOKE rows), since the membership no longer belongs to that player; orders already assigned stay theirs.
     */
    public Optional<UnlinkResult> unlink(String playerName) throws SQLException {
        List<String> warnings = new ArrayList<>();
        Optional<UnlinkResult> result = db.transaction(c -> {
            Optional<LinkRow> link = ShopStore.linkByName(c, playerName.trim());
            if (link.isEmpty()) {
                return Optional.empty();
            }
            ShopStore.deleteLink(c, link.get().player());
            int revoked = 0;
            for (MemberRow m : ShopStore.membersByEmail(c, link.get().email())) {
                MemberRow locked = ShopStore.member(c, m.memberId(), true).orElse(m);
                if (locked.grantedTier() != null
                        && syncMember(c, locked.memberId(), locked.grantedTier(), null, profile(link.get()), warnings)) {
                    revoked++;
                }
            }
            return Optional.of(new UnlinkResult(link.get(), revoked));
        });
        result.ifPresent(r -> logger.info("Store: {} unlinked from {} by staff ({} membership(s) taken back)",
                r.link().playerName(), Emails.mask(r.link().email()), r.revoked()));
        warnings.forEach(staff::warn);
        return result;
    }

    /** {@code /storeadmin grant <player> <reward>}: one MANUAL row (repeatable). Empty when there is no such reward. */
    public Optional<Reward> grant(String staffName, Profile p, String rewardKey) throws SQLException {
        Optional<Reward> reward = rewards.get().any(rewardKey);
        if (reward.isEmpty()) {
            return Optional.empty();
        }
        Reward r = reward.get();
        String by = Commands.NAME.matcher(staffName).matches() ? staffName : "console";
        long millis = clock.millis();
        Commands.Values values = new Commands.Values(p.name(), p.uuid(), 1, "MANUAL", r.key());
        NewReward row = new NewReward("MANUAL:" + by + ":" + millis + ":" + r.key() + ":GRANT", "MANUAL", by, r.key(),
                r.displayName(), "GRANT", p.uuid(), p.name(), r.target(), r.requireOnline(), r.safeToRetry(),
                Commands.toJson(r.grant(), values), false);
        db.call(c -> ShopStore.insertReward(c, row));
        logger.info("Store: {} granted {} to {} by hand", by, r.key(), p.name());
        return reward;
    }

    public record Lookup(List<LinkRow> links, List<OrderRow> orders, List<MemberRow> members, List<RewardRow> rewards) {
        public boolean isEmpty() {
            return links.isEmpty() && orders.isEmpty() && members.isEmpty() && rewards.isEmpty();
        }
    }

    /** {@code /storeadmin lookup <player|order|email>}. */
    public Lookup lookup(String query) throws SQLException {
        String q = query.trim();
        return db.call(c -> {
            List<LinkRow> links = new ArrayList<>();
            List<OrderRow> orders = new ArrayList<>();
            List<MemberRow> members = new ArrayList<>();
            List<RewardRow> rows = new ArrayList<>();
            if (q.contains("@")) {
                String email = Emails.normalize(q);
                ShopStore.linkByEmail(c, email).ifPresent(links::add);
                orders.addAll(ShopStore.ordersByEmail(c, email));
                members.addAll(ShopStore.membersByEmail(c, email));
            } else {
                String friendly = Fourthwall.cleanOrderNumber(q);
                ShopStore.order(c, q, false).ifPresent(orders::add);
                if (!friendly.isEmpty()) {
                    for (OrderRow o : ShopStore.ordersByFriendlyId(c, friendly)) {
                        if (orders.stream().noneMatch(x -> x.orderId().equals(o.orderId()))) {
                            orders.add(o);
                        }
                    }
                }
                Optional<LinkRow> link = ShopStore.linkByName(c, q);
                link.ifPresent(links::add);
                if (link.isPresent()) {
                    members.addAll(ShopStore.membersByEmail(c, link.get().email()));
                    for (OrderRow o : ShopStore.ordersByPlayer(c, link.get().player())) {
                        if (orders.stream().noneMatch(x -> x.orderId().equals(o.orderId()))) {
                            orders.add(o);
                        }
                    }
                    rows.addAll(ShopStore.rewardsForPlayer(c, link.get().player(), 10));
                }
            }
            if (rows.isEmpty() && orders.size() == 1 && orders.get(0).playerUuid() != null) {
                rows.addAll(ShopStore.rewardsWithKeyPrefix(c, "ORDER:" + orders.get(0).orderId() + ":", false));
            }
            return new Lookup(links, orders, members, rows);
        });
    }
}
