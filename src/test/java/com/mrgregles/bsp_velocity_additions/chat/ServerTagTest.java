package com.mrgregles.bsp_velocity_additions.chat;

import com.mrgregles.bsp_velocity_additions.config.Messages;
import net.kyori.adventure.key.Key;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.TextComponent;
import net.kyori.adventure.text.TranslatableComponent;
import net.kyori.adventure.text.event.HoverEvent;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.serializer.gson.GsonComponentSerializer;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import net.kyori.adventure.translation.GlobalTranslator;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.slf4j.Logger;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

/** SU-VA1: {@code <server_tag>}, the server's image from BSP-Server Utilities with the grey text as its fallback. */
public class ServerTagTest {

    private static TranslatableComponent tag(Component c) {
        return assertInstanceOf(TranslatableComponent.class, c);
    }

    /** Key, fallback, grey argument, white, font and hover, as Server Utilities' client expects them. */
    public static void assertIsTheServerTag(Component c, String server) {
        TranslatableComponent t = tag(c);
        assertEquals("bsp_server_utilities.server_image." + server.toLowerCase(Locale.ROOT), t.key());
        assertEquals("%s", t.fallback());
        assertEquals(1, t.arguments().size());
        TextComponent argument = assertInstanceOf(TextComponent.class, t.arguments().get(0).asComponent());
        assertEquals("[" + server + "]", argument.content());
        assertEquals(NamedTextColor.GRAY, argument.color());
        assertEquals(NamedTextColor.WHITE, t.color());
        assertEquals(Key.key("bsp_server_utilities", "server_tags"), t.font());
        HoverEvent<?> hover = t.hoverEvent();
        assertEquals(HoverEvent.Action.SHOW_TEXT, hover.action());
        Component shown = (Component) hover.value();
        assertEquals(server, PlainTextComponentSerializer.plainText().serialize(shown));
        assertEquals(NamedTextColor.WHITE, shown.color());
    }

    @Test
    void theTagIsTheImageKeyInLowerCaseWithTheGreyTextAsFallback() {
        assertIsTheServerTag(ServerTag.of("BSP-Spawn", Messages.defaults()), "BSP-Spawn");
    }

    @Test
    void itSurvivesTheProxysTranslatorWithKeyAndFallbackIntact() {
        Component before = ServerTag.of("BSP-SafeRoom", Messages.defaults());

        Component after = GlobalTranslator.render(before, Locale.ENGLISH);

        assertIsTheServerTag(after, "BSP-SafeRoom");
    }

    @Test
    void theJsonTheClientGetsCarriesTheFallback() {
        String json = GsonComponentSerializer.gson().serialize(ServerTag.of("BSP-Spawn", Messages.defaults()));

        assertTrue(json.contains("\"translate\":\"bsp_server_utilities.server_image.bsp-spawn\""), json);
        assertTrue(json.contains("\"fallback\":\"%s\""), json);
        assertTrue(json.contains("\"font\":\"bsp_server_utilities:server_tags\""), json);
    }

    /**
     * The same with the network's own velocity.jar (copied to server/): its Adventure renders and writes the tag. The
     * plugin's classes are loaded beside the proxy's, as on the live proxy.
     */
    @Test
    void theNetworksVelocityJarRendersAndWritesTheTagWithItsFallback() throws Exception {
        Path jar = Path.of("server", "velocity.jar");
        org.junit.jupiter.api.Assumptions.assumeTrue(Files.exists(jar), "no local copy of the proxy jar");
        java.net.URL classes = Path.of("build", "classes", "java", "main").toUri().toURL();
        java.net.URL resources = Path.of("build", "resources", "main").toUri().toURL();
        try (java.net.URLClassLoader proxy = new java.net.URLClassLoader(
                new java.net.URL[] {jar.toUri().toURL(), classes, resources}, ClassLoader.getPlatformClassLoader())) {
            Class<?> messages = proxy.loadClass(Messages.class.getName());
            Class<?> component = proxy.loadClass("net.kyori.adventure.text.Component");
            Object tag = proxy.loadClass(ServerTag.class.getName()).getMethod("of", String.class, messages)
                    .invoke(null, "BSP-Spawn", messages.getMethod("defaults").invoke(null));
            Object rendered = proxy.loadClass("net.kyori.adventure.translation.GlobalTranslator")
                    .getMethod("render", component, Locale.class).invoke(null, tag, Locale.ENGLISH);
            Class<?> gson = proxy.loadClass("net.kyori.adventure.text.serializer.gson.GsonComponentSerializer");
            Object serializer = gson.getMethod("gson").invoke(null);
            String json = (String) gson.getMethod("serialize", component).invoke(serializer, rendered);

            // The 1.20 shape a 1.20.1 client reads (the API snapshot on the test classpath writes the newer hover_event).
            assertEquals("{\"color\":\"white\",\"hoverEvent\":{\"action\":\"show_text\",\"contents\":"
                    + "{\"color\":\"white\",\"text\":\"BSP-Spawn\"}},\"font\":\"bsp_server_utilities:server_tags\","
                    + "\"translate\":\"bsp_server_utilities.server_image.bsp-spawn\",\"fallback\":\"%s\","
                    + "\"with\":[{\"color\":\"gray\",\"text\":\"[BSP-Spawn]\"}]}", json);
        }
    }

    @Test
    void theHoverComesFromMessagesToml(@TempDir Path dir) throws Exception {
        Files.writeString(dir.resolve(Messages.FILE_NAME), "[chat]\nserverTagHover = \"<aqua>Sent from <server>\"\n");
        Messages owner = Messages.load(dir, mock(Logger.class));

        Component shown = (Component) ServerTag.of("BSP-Wild", owner).hoverEvent().value();

        assertEquals("Sent from BSP-Wild", PlainTextComponentSerializer.plainText().serialize(shown));
        assertEquals(NamedTextColor.AQUA, shown.color());
    }

    @Test
    void anOldMessagesFileKeepsItsRelayStaffLineButGetsTheBundledHover(@TempDir Path dir) throws Exception {
        Files.writeString(dir.resolve(Messages.FILE_NAME), "[chat]\nrelayStaff = \"<gray>[<server>]</gray> <line>\"\n");
        Messages owner = Messages.load(dir, mock(Logger.class));

        assertEquals("<gray>[<server>]</gray> <line>", owner.raw(ChatRelayFeature.KEY_RELAY_STAFF));
        assertEquals("<white><server>", owner.raw(ServerTag.KEY_HOVER));
    }

    @Test
    void theBundledDefaultsPutTheTagInTheStaffLineOnly() {
        Messages defaults = Messages.defaults();

        assertEquals("<server_tag> <line>", defaults.raw(ChatRelayFeature.KEY_RELAY_STAFF));
        assertEquals("<line>", defaults.raw(ChatRelayFeature.KEY_RELAY));
        assertTrue(!defaults.raw("players.staffServer").contains("<server_tag>"));
    }
}
