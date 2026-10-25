package com.mrgregles.bsp_velocity_additions.discord;

import java.util.List;

/**
 * A Discord message as the bot builds it, free of any Discord library: an optional plain text line (what a phone
 * notification shows) and an optional card. Text is Discord markdown, with every inserted value already escaped.
 */
public record OutgoingMessage(String text, Card card) {

    /** Discord's limits, applied when the message is built. */
    static final int TEXT_MAX = 2000;
    static final int TITLE_MAX = 256;
    static final int DESCRIPTION_MAX = 4096;
    static final int FIELD_NAME_MAX = 256;
    static final int FIELD_VALUE_MAX = 1024;
    static final int FOOTER_MAX = 2048;

    /** One labelled box in a card; inline boxes sit side by side. */
    public record Field(String name, String value, boolean inline) {
    }

    /** A card ("embed"): the coloured edge, a title, a description, fields and a footer. Empty parts are left out. */
    public record Card(int colour, String title, String description, List<Field> fields, String footer) {

        public Card {
            title = cut(title, TITLE_MAX);
            description = cut(description, DESCRIPTION_MAX);
            footer = cut(footer, FOOTER_MAX);
            fields = fields.stream()
                    .filter(f -> !f.name().isBlank() && !f.value().isBlank())
                    .map(f -> new Field(cut(f.name(), FIELD_NAME_MAX), cut(f.value(), FIELD_VALUE_MAX), f.inline()))
                    .toList();
        }

        boolean isEmpty() {
            return title.isBlank() && description.isBlank() && fields.isEmpty() && footer.isBlank();
        }
    }

    public OutgoingMessage {
        text = cut(text, TEXT_MAX);
        if (card != null && card.isEmpty()) {
            card = null;
        }
    }

    public boolean hasCard() {
        return card != null;
    }

    public boolean isEmpty() {
        return text.isBlank() && card == null;
    }

    static String cut(String s, int max) {
        if (s == null) {
            return "";
        }
        return s.length() <= max ? s : s.substring(0, max - 1) + "…";
    }
}
