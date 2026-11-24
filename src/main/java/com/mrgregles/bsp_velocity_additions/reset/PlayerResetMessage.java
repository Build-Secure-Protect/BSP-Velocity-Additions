package com.mrgregles.bsp_velocity_additions.reset;

import java.util.Optional;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * One {@code player_reset} notice from BSP-Core's Redis channel (decision PR21). BSP-Core's messages are
 * {@code <kind>:<originServerId>:<payload>}; for this kind the payload is {@code <uuid>|<resetMs>|<by>|<name>}
 * ({@code by} has any {@code |} replaced by a space; the name is last, so it is read whole). The uuid anchors the
 * parse, so the origin may contain {@code :}.
 */
public record PlayerResetMessage(String origin, UUID player, long resetMs, String by, String name) {

    public static final String KIND = "player_reset";

    /** Anchored on the uuid, so an origin server id that contains {@code :} is read whole. */
    private static final Pattern NOTICE = Pattern.compile(
            "^" + KIND + ":(.+):([0-9a-fA-F-]{36})\\|(\\d+)\\|([^|]*)\\|(.*)$", Pattern.DOTALL);

    /** True when the text is a {@code player_reset} notice at all (well formed or not). */
    public static boolean isResetKind(String text) {
        return text != null && text.startsWith(KIND + ":");
    }

    /** Empty for another kind of message or anything malformed: such a message is ignored. */
    public static Optional<PlayerResetMessage> parse(String text) {
        if (!isResetKind(text)) {
            return Optional.empty();
        }
        Matcher m = NOTICE.matcher(text);
        if (!m.matches()) {
            return Optional.empty();
        }
        try {
            UUID player = UUID.fromString(m.group(2));
            long resetMs = Long.parseLong(m.group(3));
            if (resetMs <= 0L) {
                return Optional.empty();
            }
            return Optional.of(new PlayerResetMessage(m.group(1), player, resetMs, m.group(4), m.group(5)));
        } catch (IllegalArgumentException malformed) {
            return Optional.empty();
        }
    }
}
