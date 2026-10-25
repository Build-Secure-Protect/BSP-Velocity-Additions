package com.mrgregles.bsp_velocity_additions.discord;

import com.mrgregles.bsp_velocity_additions.config.Messages;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Builds every Discord message from the {@code [discord]} lines of {@code messages.toml}. The alert cards are BSP-Core's
 * look A (the red alarm card, picked from {@code BSP-Core/tools/preview/discord_herald_concepts.html}). Each message has
 * a {@code ...Text} line and an optional card ({@code ...Title}, {@code ...Description}); leaving both card lines empty
 * sends the text alone. Placeholders are {@code <name>}. Every name and server put in is escaped for Discord markdown,
 * so a player called {@code _Steve_} cannot turn the line italic and no value can mention anyone.
 */
public final class HeraldMessages {

    static final int RED = 0xED4245;
    static final int GREEN = 0x3BA55D;
    static final int GREY = 0x80848E;

    private static final Pattern PLACEHOLDER = Pattern.compile("<([a-zA-Z]+)>");
    /**
     * The characters that can change how a Discord line looks or whom it mentions: markdown (bold, italic, underline,
     * strike, code, spoiler, quote, masked links) and the starts of mentions and emoji. Discord shows a character with a
     * backslash before it as typed. Ordinary punctuation such as {@code -} in {@code BSP-Spawn} is left alone, so phone
     * notifications show the name plainly.
     */
    private static final Pattern MARKDOWN = Pattern.compile("([\\\\*_~`|>\\[\\]<@:])");

    private final Messages messages;

    public HeraldMessages(Messages messages) {
        this.messages = messages;
    }

    OutgoingMessage warning(Alert.Warning w) {
        Map<String, String> p = place(w.place(), w.thief());
        p.put("seconds", Integer.toString(Math.max(0, w.secondsLeft())));
        return new OutgoingMessage(line("warningText", p), new OutgoingMessage.Card(RED,
                line("warningTitle", p), line("warningDescription", p),
                List.of(new OutgoingMessage.Field(line("warningServer", p), line("warningServerValue", p), true),
                        new OutgoingMessage.Field(line("warningWhere", p), line("warningWhereValue", p), true),
                        new OutgoingMessage.Field(line("warningThief", p), line("warningThiefValue", p), true),
                        new OutgoingMessage.Field(line("warningTimeLeft", p), line("warningTimeLeftValue", p), true)),
                line("warningFooter", p)));
    }

    OutgoingMessage saved(Alert.Saved s) {
        Map<String, String> p = place(s.place(), s.thief());
        String reasonKey = switch (s.reason()) {
            case "left" -> "savedLeft";
            case "interrupted" -> "savedInterrupted";
            case "totem_gone" -> "savedTotemGone";
            case "cancelled" -> "savedCancelled";
            default -> "savedEnded";
        };
        return new OutgoingMessage(line("savedText", p), card(GREEN, line("savedTitle", p), line(reasonKey, p)));
    }

    OutgoingMessage stolen(Alert.Stolen s) {
        Map<String, String> p = place(s.place(), s.thief());
        return new OutgoingMessage(line("stolenText", p), card(RED, line("stolenTitle", p), line("stolenDescription", p)));
    }

    /** The public line. Only the server is offered to the template, so nothing else can ever appear in it. */
    OutgoingMessage channel(Alert.Channel c) {
        return reply("channel", GREY, Map.of("server", escape(c.server())));
    }

    OutgoingMessage linked(String name) {
        return reply("linked", GREEN, Map.of("name", escape(name)));
    }

    OutgoingMessage testDm(String name) {
        return reply("test", GREEN, Map.of("name", escape(name)));
    }

    OutgoingMessage replaced(String name) {
        return reply("replaced", GREY, Map.of("name", escape(name)));
    }

    OutgoingMessage linkInvalid() {
        return reply("linkInvalid", RED, Map.of());
    }

    OutgoingMessage tooManyTries() {
        return reply("tooManyTries", RED, Map.of());
    }

    OutgoingMessage unavailable() {
        return reply("unavailable", RED, Map.of());
    }

    OutgoingMessage notLinked() {
        return reply("notLinked", GREY, Map.of());
    }

    OutgoingMessage unlinkNoMatch(String typed) {
        return reply("unlinkNoMatch", GREY, Map.of("name", escape(typed)));
    }

    OutgoingMessage unlinked(List<String> names) {
        return reply("unlinked", GREY, Map.of("names", joinAnd(names)));
    }

    /** Look A (D24): the list in a grey card, with how to unlink one in the footer. */
    OutgoingMessage status(List<String> names) {
        Map<String, String> values = Map.of("names", joinAnd(names), "list", bullets(names));
        return new OutgoingMessage(line("statusText", values), new OutgoingMessage.Card(GREY,
                line("statusTitle", values), line("statusDescription", values), List.of(), line("statusFooter", values)));
    }

    private OutgoingMessage reply(String key, int colour, Map<String, String> values) {
        return new OutgoingMessage(line(key + "Text", values),
                card(colour, line(key + "Title", values), line(key + "Description", values)));
    }

    private static OutgoingMessage.Card card(int colour, String title, String description) {
        return new OutgoingMessage.Card(colour, title, description, List.of(), "");
    }

    /** Place values: the server and thief escaped, the coordinates are numbers the plugin wrote itself. */
    private static Map<String, String> place(Alert.Place place, String thief) {
        Map<String, String> p = new HashMap<>();
        p.put("server", escape(place.server()));
        p.put("x", Integer.toString(place.x()));
        p.put("y", Integer.toString(place.y()));
        p.put("z", Integer.toString(place.z()));
        p.put("thief", escape(thief));
        return p;
    }

    /**
     * The template line with its placeholders filled from already escaped values. An unknown placeholder stays as
     * written; a key missing from both the owner's file and the bundled template gives an empty line.
     */
    String line(String key, Map<String, String> values) {
        String full = "discord." + key;
        String template = messages.raw(full);
        if (template.equals(full)) {
            return "";
        }
        Matcher m = PLACEHOLDER.matcher(template);
        StringBuilder out = new StringBuilder();
        while (m.find()) {
            String value = values.get(m.group(1));
            m.appendReplacement(out, Matcher.quoteReplacement(value == null ? m.group() : value));
        }
        m.appendTail(out);
        return out.toString();
    }

    /** Discord markdown and mentions made inert. */
    static String escape(String value) {
        return MARKDOWN.matcher(value == null ? "" : value).replaceAll("\\\\$1");
    }

    /** "A", "A and B", "A, B and C", each name escaped. */
    static String joinAnd(List<String> names) {
        List<String> e = names.stream().map(HeraldMessages::escape).toList();
        if (e.isEmpty()) {
            return "";
        }
        if (e.size() == 1) {
            return e.get(0);
        }
        return String.join(", ", e.subList(0, e.size() - 1)) + " and " + e.get(e.size() - 1);
    }

    /** One "• name" line per name, each escaped. */
    static String bullets(List<String> names) {
        StringBuilder out = new StringBuilder();
        for (String n : names) {
            if (!out.isEmpty()) {
                out.append('\n');
            }
            out.append("• ").append(escape(n));
        }
        return out.toString();
    }
}
