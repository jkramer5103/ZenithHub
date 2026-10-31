package com.zenith.command.impl;

import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.viaversion.viaversion.api.protocol.version.ProtocolVersion;
import com.zenith.command.api.Command;
import com.zenith.command.api.CommandCategory;
import com.zenith.command.api.CommandContext;
import com.zenith.command.api.CommandSources;
import com.zenith.command.api.CommandUsage;
import com.zenith.network.server.ServerSession;
import net.kyori.adventure.key.Key;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.geysermc.mcprotocollib.protocol.data.game.command.CommandNode;
import org.geysermc.mcprotocollib.protocol.data.game.command.CommandType;
import org.geysermc.mcprotocollib.protocol.packet.common.clientbound.ClientboundStoreCookiePacket;
import org.jspecify.annotations.Nullable;

import java.nio.charset.StandardCharsets;
import java.util.OptionalInt;

import static com.zenith.Globals.CONFIG;

/**
 * Managed mode only: sends the player or spectator back to the ZenithHub lobby.
 */
public class LobbyCommand extends Command {
    /** Read by the manager's router to decide where a transferred player goes. */
    public static final Key TARGET_COOKIE = Key.key("zenith", "target");

    @Override
    public CommandUsage commandUsage() {
        return CommandUsage.builder()
            .name("lobby")
            .category(CommandCategory.CORE)
            .description("Returns you to the lobby, to switch to another account.")
            .usageLines("")
            .build();
    }

    @Override
    public LiteralArgumentBuilder<CommandContext> register() {
        return command("lobby").executes(c -> {
            var info = c.getSource().getInGamePlayerInfo();
            var source = c.getSource().getSource();
            if ((source != CommandSources.PLAYER && source != CommandSources.SPECTATOR) || info == null) {
                c.getSource().getEmbed()
                    .title("Only players in-game can use this command")
                    .errorColor();
                return ERROR;
            }
            var error = sendToLobby(info.session());
            if (error != null) {
                c.getSource().getEmbed()
                    .title("Unsupported Client MC Version")
                    .description(error)
                    .errorColor();
                return ERROR;
            }
            c.getSource().setNoOutput(true);
            return OK;
        });
    }

    public static boolean isLobbyCommand(final String command) {
        return command.trim().equalsIgnoreCase("lobby");
    }

    /** Sends a player or spectator back to the lobby. Returns why it can't, or null. */
    public static @Nullable String sendToLobby(final ServerSession session) {
        if (session.getProtocolVersion().olderThan(ProtocolVersion.v1_20_5)) return "The lobby needs Minecraft 1.20.5 or newer";
        session.send(new ClientboundStoreCookiePacket(TARGET_COOKIE, "lobby".getBytes(StandardCharsets.UTF_8)));
        // back the way they came, so the LAN IP and a public address both work; proxyIP only without a handshake address
        var host = session.getConnectingServerAddress();
        if (host != null && !host.isBlank() && session.getConnectingServerPort() > 0) session.transfer(host, session.getConnectingServerPort());
        else session.transfer(CONFIG.server.getProxyAddressForTransfer(), CONFIG.server.getProxyPortForTransfer());
        return null;
    }

    /** For spectators, who can always go back to the lobby, even without Zenith's spectator commands. */
    public static void sendSpectatorToLobby(final ServerSession session) {
        var error = sendToLobby(session);
        if (error != null) session.sendAsyncMessage(Component.text(error, NamedTextColor.RED));
    }

    /** A command tree with only /lobby in it, so a spectator's client suggests it. */
    public static CommandNode[] commandTree() {
        return new CommandNode[] {
            new CommandNode(CommandType.ROOT, false, false, new int[] {1}, OptionalInt.empty(), null, null, null, null),
            new CommandNode(CommandType.LITERAL, true, false, new int[0], OptionalInt.empty(), "lobby", null, null, null)
        };
    }
}
