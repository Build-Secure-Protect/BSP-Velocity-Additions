package com.mrgregles.bsp_velocity_additions.store;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.mrgregles.bsp_velocity_additions.store.ShopStore.OrderRow;
import org.slf4j.Logger;

import java.sql.SQLException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Reconciliation (SH-R1 5.6): webhooks are not guaranteed, so every {@code intervalHours}, two minutes after the proxy
 * starts and on {@code /storeadmin resync}, orders from the last {@code orderLookbackDays} and every member are read
 * from Fourthwall's API. An order the store has never seen is stored as a synthetic event {@code recon:<orderId>} and
 * processed like a webhook; a known order Fourthwall now shows as cancelled is cancelled
 * ({@code recon-cancel:<orderId>}); every member's rewards are brought in line. Runs on the store's worker only.
 */
public final class Reconciler {

    private static final Gson GSON = new Gson();

    public record Summary(int ordersChecked, int ordersNew, int ordersCancelled, int membersChecked, int membersChanged,
                          int problems, List<String> retry) {
        public String line() {
            return "Store resync: " + ordersChecked + " order(s) checked, " + ordersNew + " new, " + ordersCancelled
                    + " cancelled; " + membersChecked + " member(s) checked, " + membersChanged + " changed"
                    + (problems > 0 ? "; " + problems + " problem(s), see above" : "");
        }
    }

    private final FourthwallApi api;
    private final ShopDatabase db;
    private final StoreService service;
    private final Clock clock;
    private final Logger logger;

    public Reconciler(FourthwallApi api, ShopDatabase db, StoreService service, Clock clock, Logger logger) {
        this.api = api;
        this.db = db;
        this.service = service;
        this.clock = clock;
        this.logger = logger;
    }

    public Summary run(int lookbackDays) throws FourthwallApi.ApiException {
        Instant since = clock.instant().minus(Duration.ofDays(lookbackDays));
        int checked = 0;
        int added = 0;
        int cancelled = 0;
        int problems = 0;
        List<String> retry = new ArrayList<>();
        for (JsonObject json : api.ordersSince(since)) {
            checked++;
            try {
                Fourthwall.Order o = Fourthwall.Order.parse(json);
                Optional<OrderRow> known = db.call(c -> ShopStore.order(c, o.id(), false));
                String eventId;
                String type;
                JsonObject data;
                if (known.isEmpty() && db.call(c -> ShopStore.seenInTestEvent(c, o.id()))) {
                    continue; // a test order: only its test webhook counts (processTestEvents)
                } else if (known.isEmpty()) {
                    eventId = "recon:" + o.id();
                    type = Fourthwall.ORDER_PLACED;
                    data = json;
                } else if (o.cancelled() && !StoreService.CANCELLED.equals(known.get().state())) {
                    eventId = "recon-cancel:" + o.id();
                    type = Fourthwall.ORDER_UPDATED;
                    data = new JsonObject();
                    data.add("order", json);
                    JsonObject update = new JsonObject();
                    update.addProperty("type", "STATUS");
                    data.add("update", update);
                } else {
                    continue;
                }
                String id = eventId.length() > 64 ? eventId.substring(0, 64) : eventId;
                JsonObject envelope = new JsonObject();
                envelope.addProperty("id", id);
                envelope.addProperty("type", type);
                envelope.addProperty("testMode", false);
                envelope.addProperty("createdAt", clock.instant().toString());
                envelope.addProperty("source", "reconciliation");
                envelope.add("data", data);
                String body = GSON.toJson(envelope);
                db.call(c -> ShopStore.insertEvent(c, id, type, false, body));
                StoreService.Result result = service.processEvent(id);
                if (result.outcome() == StoreService.Outcome.RETRY) {
                    retry.add(id);
                } else if (result.outcome() == StoreService.Outcome.FAILED) {
                    problems++;
                }
                if (type.equals(Fourthwall.ORDER_PLACED)) {
                    added++;
                } else {
                    cancelled++;
                }
            } catch (Fourthwall.BadPayload | SQLException | RuntimeException e) {
                problems++;
                logger.warn("Store resync: an order could not be checked: {}", e.getMessage());
            }
        }
        int members = 0;
        int changed = 0;
        for (JsonObject json : api.members()) {
            members++;
            try {
                if (service.applyMember(Fourthwall.Member.parse(json), null).changed()) {
                    changed++;
                }
            } catch (Fourthwall.BadPayload | SQLException | RuntimeException e) {
                problems++;
                logger.warn("Store resync: a member could not be checked: {}", e.getMessage());
            }
        }
        return new Summary(checked, added, cancelled, members, changed, problems, List.copyOf(retry));
    }
}
