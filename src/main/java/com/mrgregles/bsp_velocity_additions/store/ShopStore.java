package com.mrgregles.bsp_velocity_additions.store;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Every statement the store runs on {@code bsp_shop}, each on a connection it is given (so callers choose the
 * transaction). {@code shop_rewards} rows are only ever inserted as PENDING, and only PENDING rows are ever changed
 * (to CANCELLED); the backends own every other state.
 */
public final class ShopStore {

    /** MariaDB writes {@code NOW(3)}; {@code CURRENT_TIMESTAMP(3)} is the same there and also works in H2. */
    static final String NOW = "CURRENT_TIMESTAMP(3)";

    private ShopStore() {
    }

    static boolean isDuplicate(SQLException e) {
        String s = e.getSQLState();
        return s != null && s.startsWith("23");
    }

    // ---- shop_events ----

    public record EventRow(String id, String type, boolean testMode, String body, Timestamp processedAt, String error) {
    }

    /** True when stored, false when an event with this id is already there. */
    public static boolean insertEvent(Connection c, String id, String type, boolean testMode, String body) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement("INSERT INTO shop_events (event_id, type, test_mode, body, received_at) "
                + "VALUES (?, ?, ?, ?, " + NOW + ")")) {
            ps.setString(1, id);
            ps.setString(2, type);
            ps.setBoolean(3, testMode);
            ps.setString(4, body);
            ps.executeUpdate();
            return true;
        } catch (SQLException e) {
            if (isDuplicate(e)) {
                return false;
            }
            throw e;
        }
    }

    public static Optional<EventRow> event(Connection c, String id) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(
                "SELECT event_id, type, test_mode, body, processed_at, error FROM shop_events WHERE event_id = ?")) {
            ps.setString(1, id);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) {
                    return Optional.empty();
                }
                return Optional.of(new EventRow(rs.getString(1), rs.getString(2), rs.getBoolean(3), rs.getString(4),
                        rs.getTimestamp(5), rs.getString(6)));
            }
        }
    }

    public static void markProcessed(Connection c, String id, String note) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement("UPDATE shop_events SET processed_at = " + NOW + ", error = ? WHERE event_id = ?")) {
            ps.setString(1, note);
            ps.setString(2, id);
            ps.executeUpdate();
        }
    }

    public static void markError(Connection c, String id, String error) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement("UPDATE shop_events SET error = ? WHERE event_id = ?")) {
            ps.setString(1, error.length() > 2000 ? error.substring(0, 2000) : error);
            ps.setString(2, id);
            ps.executeUpdate();
        }
    }

    /** Events never processed, oldest first. */
    public static List<String> unprocessedEvents(Connection c, int limit) throws SQLException {
        List<String> out = new ArrayList<>();
        try (PreparedStatement ps = c.prepareStatement(
                "SELECT event_id FROM shop_events WHERE processed_at IS NULL ORDER BY received_at LIMIT " + limit)) {
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    out.add(rs.getString(1));
                }
            }
        }
        return out;
    }

    /** Bodies of stored events that may hold this order (newest first); the caller checks the id after parsing. */
    public static List<String> eventBodiesMentioning(Connection c, String orderId) throws SQLException {
        List<String> out = new ArrayList<>();
        try (PreparedStatement ps = c.prepareStatement("SELECT body FROM shop_events WHERE type IN ('ORDER_PLACED', 'ORDER_UPDATED') "
                + "AND body LIKE ? ORDER BY received_at DESC LIMIT 20")) {
            ps.setString(1, "%" + orderId.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_") + "%");
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    out.add(rs.getString(1));
                }
            }
        }
        return out;
    }

    /** True when a stored test-mode event holds this order: reconciliation must never treat a test order as real. */
    public static boolean seenInTestEvent(Connection c, String orderId) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement("SELECT COUNT(*) FROM shop_events WHERE test_mode = TRUE AND body LIKE ?")) {
            ps.setString(1, "%" + orderId.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_") + "%");
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                return rs.getInt(1) > 0;
            }
        }
    }

    // ---- shop_orders ----

    public record OrderRow(String orderId, String friendlyId, String email, String usernameRaw, UUID playerUuid,
                           String playerName, String state, boolean testMode) {
    }

    private static final String ORDER_COLUMNS =
            "order_id, friendly_id, email, username_raw, player_uuid, player_name, state, test_mode";

    private static OrderRow order(ResultSet rs) throws SQLException {
        String uuid = rs.getString(5);
        return new OrderRow(rs.getString(1), rs.getString(2), rs.getString(3), rs.getString(4),
                uuid == null ? null : UUID.fromString(uuid), rs.getString(6), rs.getString(7), rs.getBoolean(8));
    }

    private static List<OrderRow> orders(PreparedStatement ps) throws SQLException {
        List<OrderRow> out = new ArrayList<>();
        try (ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                out.add(order(rs));
            }
        }
        return out;
    }

    public static Optional<OrderRow> order(Connection c, String orderId, boolean forUpdate) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement("SELECT " + ORDER_COLUMNS + " FROM shop_orders WHERE order_id = ?"
                + (forUpdate ? " FOR UPDATE" : ""))) {
            ps.setString(1, orderId);
            List<OrderRow> found = orders(ps);
            return found.isEmpty() ? Optional.empty() : Optional.of(found.get(0));
        }
    }

    public static List<OrderRow> ordersByFriendlyId(Connection c, String friendlyId) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement("SELECT " + ORDER_COLUMNS + " FROM shop_orders WHERE UPPER(friendly_id) = ? "
                + "ORDER BY created_at")) {
            ps.setString(1, friendlyId.toUpperCase(java.util.Locale.ROOT));
            return orders(ps);
        }
    }

    public static List<OrderRow> ordersByEmail(Connection c, String email) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement("SELECT " + ORDER_COLUMNS + " FROM shop_orders WHERE email = ? ORDER BY created_at")) {
            ps.setString(1, email);
            return orders(ps);
        }
    }

    public static List<OrderRow> ordersByPlayer(Connection c, UUID player) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement("SELECT " + ORDER_COLUMNS + " FROM shop_orders WHERE player_uuid = ? "
                + "ORDER BY created_at DESC LIMIT 20")) {
            ps.setString(1, player.toString());
            return orders(ps);
        }
    }

    public static void insertOrder(Connection c, Fourthwall.Order o, String state, ProfileLookup.Profile player, boolean test)
            throws SQLException {
        try (PreparedStatement ps = c.prepareStatement("INSERT INTO shop_orders (" + ORDER_COLUMNS + ", created_at, updated_at) "
                + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, " + NOW + ", " + NOW + ")")) {
            ps.setString(1, o.id());
            ps.setString(2, o.friendlyId());
            ps.setString(3, cut(o.email(), 255));
            ps.setString(4, o.username() == null ? null : cut(o.username(), 255));
            ps.setString(5, player == null ? null : player.uuid().toString());
            ps.setString(6, player == null ? null : player.name());
            ps.setString(7, state);
            ps.setBoolean(8, test);
            ps.executeUpdate();
        }
    }

    public static void setOrderState(Connection c, String orderId, String state, ProfileLookup.Profile player) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(player == null
                ? "UPDATE shop_orders SET state = ?, updated_at = " + NOW + " WHERE order_id = ?"
                : "UPDATE shop_orders SET state = ?, player_uuid = ?, player_name = ?, updated_at = " + NOW + " WHERE order_id = ?")) {
            int i = 1;
            ps.setString(i++, state);
            if (player != null) {
                ps.setString(i++, player.uuid().toString());
                ps.setString(i++, player.name());
            }
            ps.setString(i, orderId);
            ps.executeUpdate();
        }
    }

    public static void setOrderEmail(Connection c, String orderId, String email) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement("UPDATE shop_orders SET email = ?, updated_at = " + NOW + " WHERE order_id = ?")) {
            ps.setString(1, cut(email, 255));
            ps.setString(2, orderId);
            ps.executeUpdate();
        }
    }

    // ---- shop_links ----

    public record LinkRow(UUID player, String playerName, String email, String linkedBy, Timestamp linkedAt) {
    }

    private static Optional<LinkRow> link(PreparedStatement ps) throws SQLException {
        try (ResultSet rs = ps.executeQuery()) {
            if (!rs.next()) {
                return Optional.empty();
            }
            return Optional.of(new LinkRow(UUID.fromString(rs.getString(1)), rs.getString(2), rs.getString(3), rs.getString(4),
                    rs.getTimestamp(5)));
        }
    }

    private static final String LINK_COLUMNS = "player_uuid, player_name, email, linked_by, linked_at";

    public static Optional<LinkRow> linkByEmail(Connection c, String email) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement("SELECT " + LINK_COLUMNS + " FROM shop_links WHERE email = ?")) {
            ps.setString(1, email);
            return link(ps);
        }
    }

    public static Optional<LinkRow> linkByPlayer(Connection c, UUID player) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement("SELECT " + LINK_COLUMNS + " FROM shop_links WHERE player_uuid = ?")) {
            ps.setString(1, player.toString());
            return link(ps);
        }
    }

    public static Optional<LinkRow> linkByName(Connection c, String name) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement("SELECT " + LINK_COLUMNS + " FROM shop_links WHERE UPPER(player_name) = ?")) {
            ps.setString(1, name.toUpperCase(java.util.Locale.ROOT));
            return link(ps);
        }
    }

    public static void insertLink(Connection c, ProfileLookup.Profile player, String email, String by) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement("INSERT INTO shop_links (" + LINK_COLUMNS + ") VALUES (?, ?, ?, ?, " + NOW + ")")) {
            ps.setString(1, player.uuid().toString());
            ps.setString(2, player.name());
            ps.setString(3, email);
            ps.setString(4, by);
            ps.executeUpdate();
        }
    }

    public static int deleteLink(Connection c, UUID player) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement("DELETE FROM shop_links WHERE player_uuid = ?")) {
            ps.setString(1, player.toString());
            return ps.executeUpdate();
        }
    }

    // ---- shop_members ----

    public record MemberRow(String memberId, String email, String nickname, String subType, String tierId, String grantedTier) {
    }

    private static final String MEMBER_COLUMNS = "member_id, email, nickname, sub_type, tier_id, granted_tier";

    private static List<MemberRow> members(PreparedStatement ps) throws SQLException {
        List<MemberRow> out = new ArrayList<>();
        try (ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                out.add(new MemberRow(rs.getString(1), rs.getString(2), rs.getString(3), rs.getString(4), rs.getString(5),
                        rs.getString(6)));
            }
        }
        return out;
    }

    public static Optional<MemberRow> member(Connection c, String memberId, boolean forUpdate) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement("SELECT " + MEMBER_COLUMNS + " FROM shop_members WHERE member_id = ?"
                + (forUpdate ? " FOR UPDATE" : ""))) {
            ps.setString(1, memberId);
            List<MemberRow> found = members(ps);
            return found.isEmpty() ? Optional.empty() : Optional.of(found.get(0));
        }
    }

    public static List<MemberRow> membersByEmail(Connection c, String email) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement("SELECT " + MEMBER_COLUMNS + " FROM shop_members WHERE email = ? ORDER BY member_id")) {
            ps.setString(1, email);
            return members(ps);
        }
    }

    /** Inserts or updates the member's own fields; {@code granted_tier} is left as it is (null for a new member). */
    public static void saveMember(Connection c, Fourthwall.Member m, boolean exists) throws SQLException {
        String sql = exists
                ? "UPDATE shop_members SET email = ?, nickname = ?, sub_type = ?, tier_id = ?, updated_at = " + NOW + " WHERE member_id = ?"
                : "INSERT INTO shop_members (email, nickname, sub_type, tier_id, member_id, updated_at) VALUES (?, ?, ?, ?, ?, " + NOW + ")";
        try (PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setString(1, cut(m.email(), 255));
            ps.setString(2, m.nickname());
            ps.setString(3, m.type());
            ps.setString(4, m.tierId());
            ps.setString(5, m.id());
            ps.executeUpdate();
        }
    }

    public static void setGrantedTier(Connection c, String memberId, String tier) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement("UPDATE shop_members SET granted_tier = ?, updated_at = " + NOW + " WHERE member_id = ?")) {
            ps.setString(1, tier);
            ps.setString(2, memberId);
            ps.executeUpdate();
        }
    }

    // ---- shop_rewards ----

    /** A row to insert; always PENDING, attempts 0, the backend's columns null. */
    public record NewReward(String dedupeKey, String sourceType, String sourceId, String rewardKey, String displayName,
                            String action, UUID player, String playerName, String target, boolean requireOnline,
                            boolean safeToRetry, String commandsJson, boolean testMode) {
        public NewReward {
            if (dedupeKey.length() > 191) {
                throw new IllegalArgumentException("dedupe_key longer than 191 characters: " + dedupeKey);
            }
            if (sourceId.length() > 64 || rewardKey.length() > 64 || displayName.length() > 128 || target.length() > 64) {
                throw new IllegalArgumentException("a value is too long for shop_rewards");
            }
        }
    }

    /** True when written, false when a row with this dedupe key already exists (a replay). */
    public static boolean insertReward(Connection c, NewReward r) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement("INSERT INTO shop_rewards (dedupe_key, source_type, source_id, reward_key, "
                + "display_name, action, player_uuid, player_name, target, require_online, safe_to_retry, commands, status, "
                + "attempts, test_mode, created_at) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 'PENDING', 0, ?, " + NOW + ")")) {
            ps.setString(1, r.dedupeKey());
            ps.setString(2, r.sourceType());
            ps.setString(3, r.sourceId());
            ps.setString(4, r.rewardKey());
            ps.setString(5, r.displayName());
            ps.setString(6, r.action());
            ps.setString(7, r.player().toString());
            ps.setString(8, r.playerName());
            ps.setString(9, r.target());
            ps.setBoolean(10, r.requireOnline());
            ps.setBoolean(11, r.safeToRetry());
            ps.setString(12, r.commandsJson());
            ps.setBoolean(13, r.testMode());
            ps.executeUpdate();
            return true;
        } catch (SQLException e) {
            if (isDuplicate(e)) {
                return false;
            }
            throw e;
        }
    }

    public record RewardRow(long id, String dedupeKey, String sourceType, String sourceId, String rewardKey, String displayName,
                            String action, UUID player, String playerName, String target, String status, String claimedBy,
                            boolean testMode) {
    }

    private static final String REWARD_COLUMNS = "id, dedupe_key, source_type, source_id, reward_key, display_name, action, "
            + "player_uuid, player_name, target, status, claimed_by, test_mode";

    private static List<RewardRow> rewards(PreparedStatement ps) throws SQLException {
        List<RewardRow> out = new ArrayList<>();
        try (ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                out.add(new RewardRow(rs.getLong(1), rs.getString(2), rs.getString(3), rs.getString(4), rs.getString(5),
                        rs.getString(6), rs.getString(7), UUID.fromString(rs.getString(8)), rs.getString(9), rs.getString(10),
                        rs.getString(11), rs.getString(12), rs.getBoolean(13)));
            }
        }
        return out;
    }

    /** Rows whose dedupe key starts with this prefix, by id. */
    public static List<RewardRow> rewardsWithKeyPrefix(Connection c, String prefix, boolean forUpdate) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement("SELECT " + REWARD_COLUMNS + " FROM shop_rewards WHERE dedupe_key LIKE ? ORDER BY id"
                + (forUpdate ? " FOR UPDATE" : ""))) {
            ps.setString(1, prefix.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_") + "%");
            return rewards(ps);
        }
    }

    public static List<RewardRow> rewardsForPlayer(Connection c, UUID player, int limit) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement("SELECT " + REWARD_COLUMNS + " FROM shop_rewards WHERE player_uuid = ? "
                + "ORDER BY id DESC LIMIT " + limit)) {
            ps.setString(1, player.toString());
            return rewards(ps);
        }
    }

    public static Optional<String> rewardStatus(Connection c, long id) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement("SELECT status FROM shop_rewards WHERE id = ? FOR UPDATE")) {
            ps.setLong(1, id);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? Optional.of(rs.getString(1)) : Optional.empty();
            }
        }
    }

    /** The contract's only change to an existing row: PENDING to CANCELLED. True when this call cancelled it. */
    public static boolean cancelPending(Connection c, long id) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement("UPDATE shop_rewards SET status = 'CANCELLED' WHERE id = ? AND status = 'PENDING'")) {
            ps.setLong(1, id);
            return ps.executeUpdate() == 1;
        }
    }

    public static int countMembershipRows(Connection c, String memberId) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement("SELECT COUNT(*) FROM shop_rewards WHERE source_type = 'MEMBERSHIP' AND source_id = ?")) {
            ps.setString(1, memberId);
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                return rs.getInt(1);
            }
        }
    }

    private static String cut(String s, int max) {
        return s == null || s.length() <= max ? s : s.substring(0, max);
    }
}
