package com.mrgregles.bsp_velocity_additions.store;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * The parts of Fourthwall's webhook payloads (API version V1) the store reads, from their OpenAPI spec: the envelope,
 * {@code OrderV1}, {@code OrderUpdatedV1} and {@code MembershipSupporterV1}. Everything else in a payload is kept only
 * in {@code shop_events.body}.
 */
public final class Fourthwall {

    public static final String ORDER_PLACED = "ORDER_PLACED";
    public static final String ORDER_UPDATED = "ORDER_UPDATED";
    public static final String SUBSCRIPTION_PURCHASED = "SUBSCRIPTION_PURCHASED";
    public static final String SUBSCRIPTION_CHANGED = "SUBSCRIPTION_CHANGED";
    public static final String SUBSCRIPTION_EXPIRED = "SUBSCRIPTION_EXPIRED";

    private Fourthwall() {
    }

    /** A payload that is not what the spec says; the event is stored with this as its error. */
    public static final class BadPayload extends Exception {
        public BadPayload(String message) {
            super(message);
        }
    }

    public record Envelope(String id, String type, boolean testMode, JsonObject data) {

        public static Envelope parse(String body) throws BadPayload {
            JsonObject o;
            try {
                o = JsonParser.parseString(body).getAsJsonObject();
            } catch (RuntimeException e) {
                throw new BadPayload("the body is not a JSON object");
            }
            String id = string(o, "id");
            String type = string(o, "type");
            if (id == null || id.isBlank() || id.length() > 64) {
                throw new BadPayload("the envelope has no usable id");
            }
            if (type == null || type.isBlank() || type.length() > 48) {
                throw new BadPayload("the envelope has no usable type");
            }
            JsonElement test = o.get("testMode");
            if (test == null || !test.isJsonPrimitive() || !test.getAsJsonPrimitive().isBoolean()) {
                throw new BadPayload("the envelope has no testMode");
            }
            JsonElement data = o.get("data");
            return new Envelope(id, type, test.getAsBoolean(), data != null && data.isJsonObject() ? data.getAsJsonObject() : null);
        }

        public JsonObject requireData() throws BadPayload {
            if (data == null) {
                throw new BadPayload("the event has no data object");
            }
            return data;
        }
    }

    /**
     * One line of an order: its position in {@code offers}, the variant's SKU and the product's slug (either may be
     * null; digital products have no SKU, D42) and the quantity.
     */
    public record Line(int index, String sku, String slug, int quantity) {
    }

    public record Order(String id, String friendlyId, String status, String email, String username, List<Line> lines) {

        public boolean cancelled() {
            return "CANCELLED".equals(status);
        }

        public static Order parse(JsonObject o) throws BadPayload {
            String id = string(o, "id");
            if (id == null || !Commands.SOURCE_ID.matcher(id).matches()) {
                throw new BadPayload("the order has no usable id");
            }
            String friendly = cleanOrderNumber(string(o, "friendlyId"));
            if (friendly.isEmpty()) {
                throw new BadPayload("order " + id + " has no usable friendlyId");
            }
            String email = string(o, "email");
            String status = string(o, "status");
            List<Line> lines = new ArrayList<>();
            JsonElement offers = o.get("offers");
            if (offers != null && offers.isJsonArray()) {
                JsonArray array = offers.getAsJsonArray();
                for (int i = 0; i < array.size(); i++) {
                    JsonElement offer = array.get(i);
                    if (!offer.isJsonObject()) {
                        continue;
                    }
                    String slug = string(offer.getAsJsonObject(), "slug");
                    JsonElement variant = offer.getAsJsonObject().get("variant");
                    if (variant == null || !variant.isJsonObject()) {
                        lines.add(new Line(i, null, slug, 1));
                        continue;
                    }
                    JsonObject v = variant.getAsJsonObject();
                    int quantity = 1;
                    JsonElement q = v.get("quantity");
                    if (q != null && q.isJsonPrimitive() && q.getAsJsonPrimitive().isNumber()) {
                        quantity = q.getAsInt();
                    }
                    lines.add(new Line(i, string(v, "sku"), slug, quantity));
                }
            }
            return new Order(id, friendly, status == null ? "" : status,
                    Emails.normalize(email), string(o, "username"), List.copyOf(lines));
        }
    }

    /** A member; {@code tierId} is null for a NONE subscription. */
    public record Member(String id, String email, String nickname, String type, String tierId) {

        public static Member parse(JsonObject o) throws BadPayload {
            String id = string(o, "id");
            if (id == null || !Commands.SOURCE_ID.matcher(id).matches()) {
                throw new BadPayload("the member has no usable id");
            }
            JsonElement sub = o.get("subscription");
            if (sub == null || !sub.isJsonObject()) {
                throw new BadPayload("member " + id + " has no subscription");
            }
            String type = string(sub.getAsJsonObject(), "type");
            if (type == null || !List.of("ACTIVE", "CANCELLED", "SUSPENDED", "NONE").contains(type)) {
                throw new BadPayload("member " + id + " has an unknown subscription type");
            }
            String tier = null;
            JsonElement variant = sub.getAsJsonObject().get("variant");
            if (variant != null && variant.isJsonObject()) {
                tier = string(variant.getAsJsonObject(), "tierId");
                if (tier != null && (tier.isBlank() || tier.length() > 64)) {
                    throw new BadPayload("member " + id + " has an unusable tierId");
                }
            }
            String nickname = string(o, "nickname");
            if (nickname != null && nickname.length() > 255) {
                nickname = nickname.substring(0, 255);
            }
            return new Member(id, Emails.normalize(string(o, "email")), nickname, type, tier);
        }
    }

    /**
     * An order number as stored, shown and typed: letters, digits, "-" and "_" only, upper case, at most 32 (B12).
     * Anything else (a "#", spaces) is dropped, the same way for Fourthwall's value and what a player types.
     */
    public static String cleanOrderNumber(String raw) {
        if (raw == null) {
            return "";
        }
        String s = raw.replaceAll("[^A-Za-z0-9_-]", "").toUpperCase(Locale.ROOT);
        return s.length() > 32 ? s.substring(0, 32) : s;
    }

    static String string(JsonObject o, String field) {
        JsonElement e = o.get(field);
        return e == null || !e.isJsonPrimitive() ? null : e.getAsString();
    }
}
