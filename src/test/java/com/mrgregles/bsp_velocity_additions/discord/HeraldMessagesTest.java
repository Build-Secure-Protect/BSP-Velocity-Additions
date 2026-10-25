package com.mrgregles.bsp_velocity_additions.discord;

import com.mrgregles.bsp_velocity_additions.config.Messages;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.io.TempDir;
import org.slf4j.Logger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

class HeraldMessagesTest {

    final HeraldMessages messages = new HeraldMessages(Messages.defaults());

    @Test
    void everyLineTheBotAsksForIsInTheBundledTemplate() throws Exception {
        String source = Files.readString(Path.of("src/main/java/com/mrgregles/bsp_velocity_additions/discord/HeraldMessages.java"));
        String template;
        try (InputStream in = Messages.class.getResourceAsStream("/messages.toml")) {
            template = new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
        Matcher direct = Pattern.compile("line\\(\"([a-zA-Z]+)\"").matcher(source);
        while (direct.find()) {
            assertTrue(template.contains("\n" + direct.group(1) + " = "), direct.group(1));
        }
        Matcher replies = Pattern.compile("reply\\(\"([a-zA-Z]+)\"").matcher(source);
        while (replies.find()) {
            for (String part : List.of("Text", "Title", "Description")) {
                assertTrue(template.contains("\n" + replies.group(1) + part + " = "), replies.group(1) + part);
            }
        }
        Matcher reasons = Pattern.compile("-> \"(saved[a-zA-Z]+)\"").matcher(source);
        while (reasons.find()) {
            assertTrue(template.contains("\n" + reasons.group(1) + " = "), reasons.group(1));
        }
    }

    @Test
    void emptyCardLinesSendTheTextAlone() {
        OutgoingMessage replaced = messages.replaced("Mrgregles");

        assertEquals("Steal warnings for Mrgregles now go to another Discord account.", replaced.text());
        assertNull(replaced.card());
        assertFalse(replaced.hasCard());
    }

    @Test
    void thePublicLineCannotShowAnythingButTheServer(@TempDir Path dir) throws Exception {
        Files.writeString(dir.resolve(Messages.FILE_NAME), "[discord]\nchannelDescription = \"<server> <thief> <player> <x>\"\n");
        Messages owner = Messages.load(dir, mock(Logger.class));
        OutgoingMessage line = new HeraldMessages(owner).channel(new Alert.Channel("Frostwarf", 1L));

        assertEquals("Frostwarf <thief> <player> <x>", line.card().description());
    }

    @Test
    void discordsLengthLimitsAreKept() {
        OutgoingMessage m = new OutgoingMessage("x".repeat(3000),
                new OutgoingMessage.Card(1, "t".repeat(300), "", List.of(new OutgoingMessage.Field("n", "v".repeat(2000), true)), ""));

        assertEquals(OutgoingMessage.TEXT_MAX, m.text().length());
        assertEquals(OutgoingMessage.TITLE_MAX, m.card().title().length());
        assertEquals(OutgoingMessage.FIELD_VALUE_MAX, m.card().fields().get(0).value().length());
    }

    @Test
    void namesAreJoinedPlainly() {
        assertEquals("A", HeraldMessages.joinAnd(List.of("A")));
        assertEquals("A and B", HeraldMessages.joinAnd(List.of("A", "B")));
        assertEquals("A, B\\_x and C", HeraldMessages.joinAnd(List.of("A", "B_x", "C")));
        assertEquals("BSP-Spawn", HeraldMessages.escape("BSP-Spawn"));
        assertEquals("• A\n• B", HeraldMessages.bullets(List.of("A", "B")));
    }
}
