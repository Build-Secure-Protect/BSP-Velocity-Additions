package com.mrgregles.bsp_velocity_additions.store;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;

/**
 * The store's four secrets, read only from environment variables (Pterodactyl egg variables), never from a file. Their
 * values are never logged or shown; {@link #toString()} names the variables only.
 */
public final class Secrets {

    public static final String WEBHOOK_SECRET = "BSP_FOURTHWALL_WEBHOOK_SECRET";
    public static final String API_USERNAME = "BSP_FOURTHWALL_API_USERNAME";
    public static final String API_PASSWORD = "BSP_FOURTHWALL_API_PASSWORD";
    public static final String DB_PASSWORD = "BSP_SHOP_DB_PASSWORD";
    public static final List<String> ALL = List.of(WEBHOOK_SECRET, API_USERNAME, API_PASSWORD, DB_PASSWORD);

    private final String webhookSecret;
    private final String apiUsername;
    private final String apiPassword;
    private final String dbPassword;
    private final List<String> missing;

    private Secrets(String webhookSecret, String apiUsername, String apiPassword, String dbPassword, List<String> missing) {
        this.webhookSecret = webhookSecret;
        this.apiUsername = apiUsername;
        this.apiPassword = apiPassword;
        this.dbPassword = dbPassword;
        this.missing = List.copyOf(missing);
    }

    /** Reads the four variables; a blank one counts as missing. */
    public static Secrets read(Function<String, String> env) {
        List<String> missing = new ArrayList<>();
        String[] values = new String[ALL.size()];
        for (int i = 0; i < ALL.size(); i++) {
            String v = env.apply(ALL.get(i));
            if (v == null || v.isBlank()) {
                missing.add(ALL.get(i));
                values[i] = "";
            } else {
                values[i] = v.trim();
            }
        }
        return new Secrets(values[0], values[1], values[2], values[3], missing);
    }

    /** The variables that are not set, in a fixed order; empty when all four are. */
    public List<String> missing() {
        return missing;
    }

    public String webhookSecret() {
        return webhookSecret;
    }

    public String apiUsername() {
        return apiUsername;
    }

    public String apiPassword() {
        return apiPassword;
    }

    public String dbPassword() {
        return dbPassword;
    }

    @Override
    public String toString() {
        return "Secrets(missing=" + missing + ")";
    }
}
