package com.mrgregles.bsp_velocity_additions.config;

import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.slf4j.Logger;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

class MessagesTest {

    private static String plain(net.kyori.adventure.text.Component c) {
        return PlainTextComponentSerializer.plainText().serialize(c);
    }

    @Test
    void bundledDefaultsRenderWithPlaceholders() {
        Messages m = Messages.defaults();

        assertEquals("Steve joined the game", plain(m.render("joinLeave.join", Placeholder.unparsed("player", "Steve"))));
        assertEquals("Steve left the game", plain(m.render("joinLeave.leave", Placeholder.unparsed("player", "Steve"))));
    }

    @Test
    void writesTheTemplateAndMergesTheOwnersLinesOverTheDefaults(@TempDir Path dir) throws Exception {
        Messages first = Messages.load(dir, mock(Logger.class));
        assertTrue(Files.exists(dir.resolve(Messages.FILE_NAME)));
        assertEquals("<yellow><player> joined the game", first.raw("joinLeave.join"));

        Files.writeString(dir.resolve(Messages.FILE_NAME), "[joinLeave]\njoin = \"<gray>[<green>+<gray>] <white><player>\"\n");
        Messages second = Messages.load(dir, mock(Logger.class));

        assertEquals("[+] Steve", plain(second.render("joinLeave.join", Placeholder.unparsed("player", "Steve"))));
        assertEquals("Steve left the game", plain(second.render("joinLeave.leave", Placeholder.unparsed("player", "Steve"))),
                "a line the owner did not write keeps its default");
    }

    @Test
    void anUnknownKeyShowsTheKeyItself() {
        assertEquals("nope.missing", plain(Messages.defaults().render("nope.missing")));
    }

    @Test
    void aBrokenFileFallsBackToTheDefaults(@TempDir Path dir) throws Exception {
        Files.writeString(dir.resolve(Messages.FILE_NAME), "[joinLeave\njoin = ");

        assertEquals("<yellow><player> left the game", Messages.load(dir, mock(Logger.class)).raw("joinLeave.leave"));
    }
}
