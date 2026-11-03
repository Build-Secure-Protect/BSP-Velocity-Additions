package com.mrgregles.bsp_velocity_additions.chat;

import com.mrgregles.bsp_velocity_additions.config.Messages;
import net.kyori.adventure.key.Key;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.HoverEvent;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;

import java.util.Locale;

/**
 * The {@code <server_tag>} placeholder (Server Utilities request SU-VA1, decision D32): a server's image in chat.
 * BSP-Server Utilities' client answers the key {@code bsp_server_utilities.server_image.<name in lower case>} with a
 * private-use character in the font {@code bsp_server_utilities:server_tags}. A server with no image, the console and
 * logs read the fallback {@code %s}, which is the grey {@code [BSP-Spawn]} argument. The whole piece is white because a
 * glyph takes the text colour. The key rule and the font name are fixed; nothing else links the two mods.
 */
public final class ServerTag {

    public static final String KEY_PREFIX = "bsp_server_utilities.server_image.";
    public static final Key FONT = Key.key("bsp_server_utilities", "server_tags");
    public static final String KEY_HOVER = "chat.serverTagHover";

    private ServerTag() {
    }

    public static Component of(String server, Messages messages) {
        return Component.translatable(KEY_PREFIX + server.toLowerCase(Locale.ROOT), "%s",
                        Component.text("[" + server + "]", NamedTextColor.GRAY))
                .color(NamedTextColor.WHITE)
                .font(FONT)
                .hoverEvent(HoverEvent.showText(messages.render(KEY_HOVER, Placeholder.unparsed("server", server))));
    }

    /** {@code <server_tag>} for a messages line. */
    public static TagResolver placeholder(String server, Messages messages) {
        return Placeholder.component("server_tag", of(server, messages));
    }
}
