package com.mrgregles.bsp_velocity_additions.store;

import java.util.Locale;
import java.util.regex.Pattern;

/** Emails are compared lower-cased and trimmed, and are only ever shown masked: {@code g***@domain.com}. */
public final class Emails {

    private static final Pattern SHAPE = Pattern.compile("^[^@\\s]{1,64}@[^@\\s]{1,189}$");

    private Emails() {
    }

    /** Lower-cased and trimmed; never null. */
    public static String normalize(String email) {
        return email == null ? "" : email.trim().toLowerCase(Locale.ROOT);
    }

    /** One @, something on either side, no spaces, at most 255 characters. */
    public static boolean looksValid(String normalized) {
        return normalized.length() <= 255 && SHAPE.matcher(normalized).matches();
    }

    /** {@code g***@domain.com}; {@code ***} for anything that is not an email. */
    public static String mask(String email) {
        String e = normalize(email);
        int at = e.indexOf('@');
        if (at <= 0 || at == e.length() - 1) {
            return "***";
        }
        return e.charAt(0) + "***" + e.substring(at);
    }
}
