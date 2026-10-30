package com.zenith.command.impl;

import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.zenith.command.api.Command;
import com.zenith.command.api.CommandCategory;
import com.zenith.command.api.CommandContext;
import com.zenith.command.api.CommandUsage;
import com.zenith.control.AfkZone;

import static com.zenith.Globals.MODULE;

/**
 * ZenithHub: the web UI's AFK zone controls.
 */
public class AfkZoneCommand extends Command {
    @Override
    public CommandUsage commandUsage() {
        return CommandUsage.builder()
            .name("afkZone")
            .category(CommandCategory.MODULE)
            .description("""
            Takes the bot to the server's AFK zone, and keeps it there if keep is on.
            """)
            .usageLines(
                "keep on/off",
                "enter",
                "leave"
            )
            .build();
    }

    @Override
    public LiteralArgumentBuilder<CommandContext> register() {
        return command("afkZone")
            .then(literal("keep")
                .then(literal("on").executes(c -> { return keep(c.getSource(), true); }))
                .then(literal("off").executes(c -> { return keep(c.getSource(), false); })))
            .then(literal("enter").executes(c -> {
                if (!MODULE.get(AfkZone.class).enterNow()) return offline(c.getSource());
                c.getSource().getEmbed().title("Going to the AFK zone").primaryColor();
                return OK;
            }))
            .then(literal("leave").executes(c -> {
                if (!MODULE.get(AfkZone.class).leaveNow()) return offline(c.getSource());
                c.getSource().getEmbed().title("Leaving the AFK zone").primaryColor();
                return OK;
            }));
    }

    private static int keep(CommandContext ctx, boolean keep) {
        MODULE.get(AfkZone.class).setKeep(keep);
        ctx.getEmbed()
            .title(keep ? "Keeping the bot in the AFK zone" : "No longer keeping the bot in the AFK zone")
            .primaryColor();
        return OK;
    }

    private static int offline(CommandContext ctx) {
        ctx.getEmbed().title("Not in the game").description("Connect first.").errorColor();
        return ERROR;
    }
}
