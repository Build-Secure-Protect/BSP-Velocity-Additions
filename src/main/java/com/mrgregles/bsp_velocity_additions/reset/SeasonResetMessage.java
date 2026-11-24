package com.mrgregles.bsp_velocity_additions.reset;

import java.util.Optional;
import java.util.OptionalInt;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * One {@code season_reset} notice from BSP-Core's Redis channel (decision D45, BSP-Core's SR2 and SR15). BSP-Core's
 * messages are {@code <kind>:<originServerId>:<payload>}; for this kind the payload is the new reset number
 * ({@code SeasonData.resetEpoch}, a positive whole number that goes up by one with every season reset), sent by
 * {@code NetworkStorage.seasonResetPublish}, followed by {@code |} and the number of the season that is starting
 * ({@code NetworkStorage.seasonResetPayload}, request VA-C4): {@code season_reset:BSP-Spawn:3|12}. A BSP-Core from
 * before VA-C4 sends the reset number alone, which is still read. As BSP-Core asks of its readers, the reset number is
 * read up to the first {@code |}: further fields a later BSP-Core may add are ignored, and a second field that is not
 * a season number means "no number". The parse is anchored on the payload at the end, so the origin may contain
 * {@code :} (never {@code |}).
 */
public record SeasonResetMessage(String origin, int resetNumber, OptionalInt season) {

    public static final String KIND = "season_reset";

    private static final Pattern NOTICE = Pattern.compile("^" + KIND + ":([^|\\r\\n]+):(\\d{1,9})(?:\\|([^:\\r\\n]*))?\\z");
    private static final Pattern SEASON = Pattern.compile("\\d{1,9}");

    /** True when the text is a {@code season_reset} notice at all (well formed or not). */
    public static boolean isSeasonResetKind(String text) {
        return text != null && text.startsWith(KIND + ":");
    }

    /** Empty for another kind of message or anything malformed: such a message is ignored. */
    public static Optional<SeasonResetMessage> parse(String text) {
        if (!isSeasonResetKind(text)) {
            return Optional.empty();
        }
        Matcher m = NOTICE.matcher(text);
        if (!m.matches()) {
            return Optional.empty();
        }
        int reset = Integer.parseInt(m.group(2));
        if (reset <= 0) {
            return Optional.empty();
        }
        OptionalInt season = OptionalInt.empty();
        if (m.group(3) != null) {
            String first = m.group(3).split("\\|", -1)[0];
            if (SEASON.matcher(first).matches() && Integer.parseInt(first) > 0) {
                season = OptionalInt.of(Integer.parseInt(first));
            }
        }
        return Optional.of(new SeasonResetMessage(m.group(1), reset, season));
    }
}
