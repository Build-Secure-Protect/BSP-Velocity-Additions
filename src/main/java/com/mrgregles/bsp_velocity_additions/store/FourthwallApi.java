package com.mrgregles.bsp_velocity_additions.store;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;

/**
 * Fourthwall's Platform API with HTTP Basic auth (an Open API user), read only: List Orders
 * ({@code GET /open-api/v1.0/order}), Get Order ({@code /order/{orderId}}), List Members
 * ({@code /memberships/members}). Pages of 100 ({@code page} from 0, {@code totalPages}). The documented limit is 100
 * requests per 10 s per shop; calls here are spaced 200 ms apart, and a 429 waits 10 s and tries again (5 times).
 */
public final class FourthwallApi {

    public static final String PREFIX = "/open-api/v1.0";
    private static final int PAGE_SIZE = 100;
    private static final int MAX_PAGES = 200;

    /** The API could not be used; the message never holds the credentials. */
    public static final class ApiException extends Exception {
        public ApiException(String message) {
            super(message);
        }
    }

    private final HttpClient http;
    private final String baseUrl;
    private final String authorization;
    private final MojangProfiles.Sleeper sleeper;
    private final long spacingMs;
    private long lastCall;

    public FourthwallApi(HttpClient http, String baseUrl, String username, String password, MojangProfiles.Sleeper sleeper, long spacingMs) {
        this.http = http;
        this.baseUrl = baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
        this.authorization = "Basic " + Base64.getEncoder().encodeToString((username + ":" + password).getBytes(StandardCharsets.UTF_8));
        this.sleeper = sleeper;
        this.spacingMs = spacingMs;
    }

    /** Orders created after {@code since}, every page. */
    public List<JsonObject> ordersSince(Instant since) throws ApiException {
        String from = URLEncoder.encode(DateTimeFormatter.ISO_INSTANT.format(since.truncatedTo(java.time.temporal.ChronoUnit.SECONDS)), StandardCharsets.UTF_8);
        return pages(PREFIX + "/order?createdAt%5Bgt%5D=" + from + "&");
    }

    public List<JsonObject> members() throws ApiException {
        return pages(PREFIX + "/memberships/members?");
    }

    public JsonObject order(String orderId) throws ApiException {
        JsonElement e = get(PREFIX + "/order/" + URLEncoder.encode(orderId, StandardCharsets.UTF_8));
        if (e == null || !e.isJsonObject()) {
            throw new ApiException("Get Order answered something other than an order");
        }
        return e.getAsJsonObject();
    }

    private List<JsonObject> pages(String pathWithQuery) throws ApiException {
        List<JsonObject> out = new ArrayList<>();
        for (int page = 0; page < MAX_PAGES; page++) {
            JsonElement e = get(pathWithQuery + "page=" + page + "&size=" + PAGE_SIZE);
            if (e == null || !e.isJsonObject() || !e.getAsJsonObject().has("results")) {
                throw new ApiException("a list answered without results");
            }
            JsonObject o = e.getAsJsonObject();
            JsonArray results = o.getAsJsonArray("results");
            for (JsonElement r : results) {
                if (r.isJsonObject()) {
                    out.add(r.getAsJsonObject());
                }
            }
            int totalPages = o.has("totalPages") && o.get("totalPages").isJsonPrimitive() ? o.get("totalPages").getAsInt() : 0;
            if (results.isEmpty() || page + 1 >= totalPages) {
                return out;
            }
        }
        return out;
    }

    private JsonElement get(String pathWithQuery) throws ApiException {
        HttpRequest request = HttpRequest.newBuilder(URI.create(baseUrl + pathWithQuery)).timeout(Duration.ofSeconds(20))
                .header("Authorization", authorization).header("Accept", "application/json")
                .header("Accept-Encoding", HttpBodies.ACCEPT).GET().build();
        String last = "";
        for (int attempt = 0; attempt < 5; attempt++) {
            try {
                pace();
                HttpResponse<byte[]> response = http.send(request, HttpResponse.BodyHandlers.ofByteArray());
                int status = response.statusCode();
                if (status == 200) {
                    return parse(response);
                }
                if (status == 401 || status == 403) {
                    throw new ApiException("Fourthwall refused the API login (HTTP " + status
                            + "); check BSP_FOURTHWALL_API_USERNAME and BSP_FOURTHWALL_API_PASSWORD");
                }
                if (status == 404) {
                    throw new ApiException("not found (HTTP 404)");
                }
                last = "HTTP " + status;
                if (status == 429) {
                    sleeper.sleep(10_000);
                } else if (status >= 500) {
                    sleeper.sleep(2_000L << attempt);
                } else {
                    throw new ApiException("Fourthwall answered HTTP " + status);
                }
            } catch (HttpBodies.UnknownEncoding e) {
                throw new ApiException("Fourthwall sent " + e.getMessage());
            } catch (IOException e) {
                last = e.getClass().getSimpleName();
                try {
                    sleeper.sleep(2_000L << attempt);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    throw new ApiException("interrupted");
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new ApiException("interrupted");
            } catch (RuntimeException e) {
                throw new ApiException("Fourthwall's answer could not be read (" + e.getClass().getSimpleName() + ")");
            }
        }
        throw new ApiException("Fourthwall did not answer (" + last + ")");
    }

    /** The answer as JSON; a failure names the status, type and encoding, never the body (it can hold emails). */
    private static JsonElement parse(HttpResponse<byte[]> response) throws IOException, ApiException {
        String text = HttpBodies.text(response);
        try {
            return JsonParser.parseString(text);
        } catch (RuntimeException e) {
            String why = String.valueOf(e.getMessage());
            int cut = why.indexOf(" See ");
            throw new ApiException("Fourthwall's answer could not be read (HTTP " + response.statusCode() + ", "
                    + response.headers().firstValue("Content-Type").orElse("no content type") + ", encoding "
                    + HttpBodies.encoding(response) + ", " + response.body().length + " bytes: "
                    + (cut > 0 ? why.substring(0, cut) : why) + ")");
        }
    }

    private void pace() throws InterruptedException {
        long wait = lastCall + spacingMs - System.currentTimeMillis();
        if (wait > 0) {
            sleeper.sleep(wait);
        }
        lastCall = System.currentTimeMillis();
    }
}
