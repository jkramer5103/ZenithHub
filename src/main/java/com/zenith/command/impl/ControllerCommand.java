package com.zenith.command.impl;

import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.viaversion.viaversion.api.protocol.version.ProtocolVersion;
import com.zenith.Proxy;
import com.zenith.command.api.Command;
import com.zenith.command.api.CommandCategory;
import com.zenith.command.api.CommandContext;
import com.zenith.command.api.CommandUsage;
import com.zenith.control.ManagerCommandSource;
import com.zenith.network.server.ServerSession;
import org.jspecify.annotations.Nullable;

import static com.mojang.brigadier.arguments.StringArgumentType.getString;
import static com.mojang.brigadier.arguments.StringArgumentType.string;
import static com.zenith.Globals.EXECUTOR;
import static com.zenith.Globals.PLAYER_LISTS;
import static com.zenith.discord.DiscordBot.escape;
import static java.util.concurrent.TimeUnit.SECONDS;

/**
 * Managed mode only: the web UI's "Make player" and "Make spectator" buttons.
 *
 * Works like the in-game {@code swap force}, but anyone with control of the instance can pick who plays.
 */
public class ControllerCommand extends Command {
    @Override
    public CommandUsage commandUsage() {
        return CommandUsage.builder()
            .name("controller")
            .category(CommandCategory.MANAGE)
            .description("""
            Makes a spectator the controlling player, or sends the controlling player to spectator mode.
            """)
            .usageLines(
                "set <player>",
                "release"
            )
            .build();
    }

    @Override
    public LiteralArgumentBuilder<CommandContext> register() {
        return command("controller").requires(c -> c.getSource() instanceof ManagerCommandSource)
            .then(literal("set").then(argument("player", string()).executes(c -> {
                var name = getString(c, "player");
                var proxy = Proxy.getInstance();
                var target = proxy.getSpectatorConnections().stream()
                    .filter(s -> s.getName().equalsIgnoreCase(name))
                    .findFirst()
                    .orElse(null);
                if (target == null) {
                    error(c.getSource(), "Unable to swap", escape(name) + " isn't spectating");
                    return ERROR;
                }
                var profile = target.getProfileCache().getProfile();
                if (profile == null || !PLAYER_LISTS.getWhitelist().contains(profile.getId())) {
                    error(c.getSource(), "Unable to swap", escape(name) + " isn't allowed to play this account");
                    return ERROR;
                }
                if (tooOld(c.getSource(), target)) return ERROR;
                var current = proxy.getActivePlayer();
                if (current != null) {
                    if (tooOld(c.getSource(), current)) return ERROR;
                    current.transferToSpectator();
                    // give the old player a moment to leave before the new one takes over
                    EXECUTOR.schedule(() -> target.transferToControllingPlayer(), 1, SECONDS);
                } else {
                    target.transferToControllingPlayer();
                }
                c.getSource().getEmbed()
                    .title(escape(target.getName()) + " is now playing")
                    .primaryColor();
                return OK;
            })))
            .then(literal("release").executes(c -> {
                var current = Proxy.getInstance().getActivePlayer();
                if (current == null) {
                    error(c.getSource(), "Unable to swap", "Nobody is playing on the account");
                    return ERROR;
                }
                if (tooOld(c.getSource(), current)) return ERROR;
                current.transferToSpectator();
                c.getSource().getEmbed()
                    .title(escape(current.getName()) + " is now spectating")
                    .primaryColor();
                return OK;
            }));
    }

    /** Swapping reconnects the player with a transfer, which needs 1.20.5. */
    private static boolean tooOld(CommandContext ctx, @Nullable ServerSession session) {
        if (session == null || session.getProtocolVersion().newerThanOrEqualTo(ProtocolVersion.v1_20_5)) return false;
        error(ctx, "Unsupported Client MC Version", escape(session.getName()) + " needs Minecraft 1.20.5 or newer to swap");
        return true;
    }

    private static void error(CommandContext ctx, String title, String description) {
        ctx.getEmbed()
            .title(title)
            .description(description)
            .errorColor();
    }
}
