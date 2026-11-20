package com.mrgregles.bsp_velocity_additions.store;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;

/**
 * Looks names up at Mojang ({@code GET <profileUrl><name>}): 200 gives the id (32 hex digits, made hyphenated) and
 * the correctly cased name; 204 or 404 means no such player. 429 and 5xx are tried again after 1, 2 and 4 s, then
 * reported as {@link Unavailable}. Answers, found or not, are kept for {@code cacheMinutes}.
 */
public final class MojangProfiles implements ProfileLookup {

    private static final Pattern HEX32 = Pattern.compile("^[0-9a-fA-F]{32}$");
    private static final long[] BACKOFF_MS = {1_000, 2_000, 4_000};

    @FunctionalInterface
    public interface Sleeper {
        void sleep(long millis) throws InterruptedException;
    }

    private record Cached(Optional<Profile> profile, Instant until) {
    }

    private final HttpClient http;
    private final String profileUrl;
    private final Duration cacheFor;
    private final Clock clock;
    private final Sleeper sleeper;
    private final Map<String, Cached> cache = new ConcurrentHashMap<>();

    public MojangProfiles(HttpClient http, String profileUrl, Duration cacheFor, Clock clock, Sleeper sleeper) {
        this.http = http;
        this.profileUrl = profileUrl.endsWith("/") ? profileUrl : profileUrl + "/";
        this.cacheFor = cacheFor;
        this.clock = clock;
        this.sleeper = sleeper;
    }

    @Override
    public Optional<Profile> find(String raw) throws Unavailable {
        String name = raw == null ? "" : raw.trim();
        if (!Commands.NAME.matcher(name).matches()) {
            return Optional.empty();
        }
        String key = name.toLowerCase(Locale.ROOT);
        Cached hit = cache.get(key);
        Instant now = clock.instant();
        if (hit != null && now.isBefore(hit.until())) {
            return hit.profile();
        }
        Optional<Profile> found = ask(name);
        if (!cacheFor.isZero()) {
            cache.put(key, new Cached(found, now.plus(cacheFor)));
        }
        return found;
    }

    private Optional<Profile> ask(String name) throws Unavailable {
        HttpRequest request = HttpRequest.newBuilder(URI.create(profileUrl + name))
                .timeout(Duration.ofSeconds(5)).header("Accept", "application/json").header("Accept-Encoding", HttpBodies.ACCEPT)
                .GET().build();
        String last = "";
        for (int attempt = 0; ; attempt++) {
            try {
                HttpResponse<byte[]> response = http.send(request, HttpResponse.BodyHandlers.ofByteArray());
                int status = response.statusCode();
                if (status == 200) {
                    return parse(HttpBodies.text(response)); // B16: unpacked if compressed
                }
                if (status == 204 || status == 404) {
                    return Optional.empty();
                }
                if (status != 429 && status < 500) {
                    throw new Unavailable("Mojang answered HTTP " + status);
                }
                last = "HTTP " + status;
            } catch (HttpBodies.UnknownEncoding e) {
                throw new Unavailable("Mojang sent " + e.getMessage());
            } catch (IOException e) {
                last = e.getClass().getSimpleName();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new Unavailable("interrupted");
            }
            if (attempt >= BACKOFF_MS.length) {
                throw new Unavailable("Mojang did not answer (" + last + ") after " + (attempt + 1) + " tries");
            }
            try {
                sleeper.sleep(BACKOFF_MS[attempt]);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new Unavailable("interrupted");
            }
        }
    }

    static Optional<Profile> parse(String body) throws Unavailable {
        try {
            JsonObject o = JsonParser.parseString(body).getAsJsonObject();
            String id = o.get("id").getAsString();
            String name = o.get("name").getAsString();
            if (!HEX32.matcher(id).matches() || !Commands.NAME.matcher(name).matches()) {
                return Optional.empty();
            }
            return Optional.of(new Profile(hyphenate(id), name));
        } catch (RuntimeException e) {
            throw new Unavailable("Mojang's answer could not be read");
        }
    }

    static UUID hyphenate(String hex32) {
        String h = hex32.toLowerCase(Locale.ROOT);
        return UUID.fromString(h.substring(0, 8) + "-" + h.substring(8, 12) + "-" + h.substring(12, 16) + "-"
                + h.substring(16, 20) + "-" + h.substring(20));
    }
}
