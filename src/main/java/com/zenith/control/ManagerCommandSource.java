package com.zenith.control;

import com.zenith.command.api.CommandContext;
import com.zenith.command.api.CommandSource;
import com.zenith.discord.Embed;

/**
 * Commands issued through the manager (web UI, central Discord bot, lobby).
 *
 * The manager has already checked the actor's permissions before forwarding the command.
 * Output is returned to the manager instead of being logged here.
 */
public record ManagerCommandSource(String actor) implements CommandSource {
    @Override
    public String name() {
        return "Manager";
    }

    @Override
    public boolean validateAccountOwner(CommandContext ctx) {
        return true;
    }

    @Override
    public void logEmbed(CommandContext ctx, Embed embed) {}
}
