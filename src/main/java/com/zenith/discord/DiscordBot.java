package com.zenith.discord;

import com.zenith.command.api.CommandOutputHelper;
import com.zenith.event.discord.MainChannelMessageSentEvent;
import com.zenith.util.MentionUtil;
import org.jspecify.annotations.Nullable;

import java.time.Instant;

import static com.zenith.Globals.*;

/**
 * Discord messages from this instance.
 *
 * Zenith no longer logs in to Discord itself: messages are posted as {@link MainChannelMessageSentEvent}s,
 * which the control API forwards to the manager's central Discord bot.
 */
public class DiscordBot {
    public static String mentionAccountOwner() {
        return mentionRole(CONFIG.discord.accountOwnerRoleId);
    }

    public static String mentionRole(final String roleId) {
        // the manager turns any role mention into a ping of the account owner
        return MentionUtil.forRole(roleId);
    }

    public static String escape(String message) {
        return message.replace("_", "\\_").replace("*", "\\*").replace("`", "\\`");
    }

    public static String unescape(String message) {
        return message.replace("\\_", "_").replace("\\*", "*").replace("\\`", "`");
    }

    public void sendEmbedMessage(Embed embed) {
        sendEmbedMessage(null, embed);
    }

    public void sendEmbedMessage(@Nullable String message, Embed embed) {
        if (embed.timestamp() == null) embed.timestamp(Instant.now());
        if (message != null) TERMINAL_LOG.info(message);
        CommandOutputHelper.logEmbedOutputToTerminal(embed);
        EVENT_BUS.postAsync(new MainChannelMessageSentEvent(embed, message));
    }

    public void sendMessage(final String message) {
        TERMINAL_LOG.info(message);
        EVENT_BUS.postAsync(new MainChannelMessageSentEvent(null, message));
    }
}
