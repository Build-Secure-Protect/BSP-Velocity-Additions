package com.mrgregles.bsp_velocity_additions.store;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.zip.GZIPInputStream;
import java.util.zip.Inflater;
import java.util.zip.InflaterInputStream;

/**
 * Answer bodies from Fourthwall and Mojang as text. Fourthwall's API compresses its answers even when not asked (bug
 * B16), and Java's HTTP client does not unpack them, so requests ask for gzip or deflate openly and the answer is
 * unpacked here. Any other encoding is refused by name. Bodies are never put in a message: they can hold emails.
 */
final class HttpBodies {

    static final String ACCEPT = "gzip, deflate";

    /** The encoding could not be unpacked. */
    static final class UnknownEncoding extends IOException {
        final String encoding;

        UnknownEncoding(String encoding) {
            super("an answer compressed as \"" + encoding + "\", which cannot be unpacked");
            this.encoding = encoding;
        }
    }

    private HttpBodies() {
    }

    static String encoding(HttpResponse<?> r) {
        return r.headers().firstValue("Content-Encoding").orElse("identity").trim().toLowerCase(Locale.ROOT);
    }

    static String text(HttpResponse<byte[]> r) throws IOException {
        byte[] body = r.body() == null ? new byte[0] : r.body();
        String encoding = encoding(r);
        byte[] plain = switch (encoding) {
            case "", "identity" -> body;
            case "gzip", "x-gzip" -> read(new GZIPInputStream(new ByteArrayInputStream(body)));
            case "deflate" -> inflate(body);
            default -> throw new UnknownEncoding(encoding);
        };
        return new String(plain, StandardCharsets.UTF_8);
    }

    /** "deflate" is meant to be zlib-wrapped, but some servers send it raw; both are accepted. */
    private static byte[] inflate(byte[] body) throws IOException {
        try {
            return read(new InflaterInputStream(new ByteArrayInputStream(body)));
        } catch (IOException zlibFailed) {
            return read(new InflaterInputStream(new ByteArrayInputStream(body), new Inflater(true)));
        }
    }

    private static byte[] read(InputStream in) throws IOException {
        try (in) {
            return in.readAllBytes();
        }
    }
}
