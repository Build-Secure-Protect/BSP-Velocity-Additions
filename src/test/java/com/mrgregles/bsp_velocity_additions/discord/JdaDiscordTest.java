package com.mrgregles.bsp_velocity_additions.discord;

import net.dv8tion.jda.api.entities.MessageEmbed;
import net.dv8tion.jda.api.utils.messages.MessageCreateData;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Only the message conversion; logging in, commands and sending need the real Discord and are untested. */
class JdaDiscordTest {

    @Test
    void aMessageBecomesTextPlusOneEmbedWithNoMentionsAllowed() {
        MessageCreateData data = JdaDiscord.data(new OutgoingMessage("Your totem on Frostwarf is being stolen!",
                new OutgoingMessage.Card(HeraldMessages.RED, "⚠ Totem under attack", "",
                        List.of(new OutgoingMessage.Field("Server", "Frostwarf", true)), "Log in.")));

        assertEquals("Your totem on Frostwarf is being stolen!", data.getContent());
        MessageEmbed embed = data.getEmbeds().get(0);
        assertEquals(0xED4245, embed.getColorRaw());
        assertEquals("⚠ Totem under attack", embed.getTitle());
        assertEquals("Frostwarf", embed.getFields().get(0).getValue());
        assertTrue(embed.getFields().get(0).isInline());
        assertEquals("Log in.", embed.getFooter().getText());
        assertTrue(data.toData().getObject("allowed_mentions").getArray("parse").isEmpty());
    }

    @Test
    void aTextOnlyMessageHasNoEmbedAndACardOnlyMessageNoText() {
        assertTrue(JdaDiscord.data(new OutgoingMessage("Herald test.", null)).getEmbeds().isEmpty());
        MessageCreateData card = JdaDiscord.data(new OutgoingMessage("",
                new OutgoingMessage.Card(HeraldMessages.GREY, "", "A totem is being stolen on **Frostwarf**", List.of(), "")));
        assertEquals("", card.getContent());
        assertEquals("A totem is being stolen on **Frostwarf**", card.getEmbeds().get(0).getDescription());
    }
}
