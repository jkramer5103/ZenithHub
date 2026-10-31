package com.zenith.command.impl;

import com.google.gson.JsonObject;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.zenith.command.api.Command;
import com.zenith.command.api.CommandCategory;
import com.zenith.command.api.CommandContext;
import com.zenith.command.api.CommandUsage;
import com.zenith.control.ControlServer;
import com.zenith.discord.DiscordBot;
import com.zenith.discord.Embed;

import static com.mojang.brigadier.arguments.StringArgumentType.greedyString;
import static com.zenith.Globals.CONFIG;
import static com.zenith.Globals.DISCORD;
import static com.zenith.command.brigadier.CustomStringArgumentType.getString;

/**
 * ZenithHub: the "Notify me" task action. Tells the account owner in the web UI and, if switched on, on Discord.
 */
public class NotifyCommand extends Command {
    @Override
    public CommandUsage commandUsage() {
        return CommandUsage.builder()
            .name("notify")
            .category(CommandCategory.MANAGE)
            .description("""
            Sends the account owner a notification in the web UI and on Discord.
            """)
            .usageLines("<message>")
            .build();
    }

    @Override
    public LiteralArgumentBuilder<CommandContext> register() {
        return command("notify")
            .then(argument("message", greedyString()).executes(c -> {
                var message = getString(c, "message").strip();
                if (message.length() > 200) message = message.substring(0, 200);
                var json = new JsonObject();
                json.addProperty("type", "alert");
                json.addProperty("ts", System.currentTimeMillis());
                json.addProperty("text", message);
                ControlServer.INSTANCE.publish(json);
                if (CONFIG.discord.notifications.taskAlerts) {
                    var embed = Embed.builder().title("Notification").description(DiscordBot.escape(message)).primaryColor();
                    if (CONFIG.discord.notifications.taskAlertsMention) DISCORD.sendEmbedMessage(DiscordBot.mentionAccountOwner(), embed);
                    else DISCORD.sendEmbedMessage(embed);
                }
                c.getSource().getEmbed().title("Notification sent").description(message).primaryColor();
                return OK;
            }));
    }
}
