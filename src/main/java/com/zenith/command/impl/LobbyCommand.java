package com.zenith.command.impl;

import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.viaversion.viaversion.api.protocol.version.ProtocolVersion;
import com.zenith.command.api.Command;
import com.zenith.command.api.CommandCategory;
import com.zenith.command.api.CommandContext;
import com.zenith.command.api.CommandSources;
import com.zenith.command.api.CommandUsage;
import net.kyori.adventure.key.Key;
import org.geysermc.mcprotocollib.protocol.packet.common.clientbound.ClientboundStoreCookiePacket;

import java.nio.charset.StandardCharsets;

import static com.zenith.Globals.CONFIG;

/**
 * Managed mode only: sends the player back to the ZenithProxy manager's lobby.
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
            if (c.getSource().getSource() != CommandSources.PLAYER || info == null) {
                c.getSource().getEmbed()
                    .title("Only players in-game can use this command")
                    .errorColor();
                return ERROR;
            }
            var session = info.session();
            if (session.getProtocolVersion().olderThan(ProtocolVersion.v1_20_5)) {
                c.getSource().getEmbed()
                    .title("Unsupported Client MC Version")
                    .description("The lobby needs Minecraft 1.20.5 or newer")
                    .errorColor();
                return ERROR;
            }
            c.getSource().setNoOutput(true);
            session.send(new ClientboundStoreCookiePacket(TARGET_COOKIE, "lobby".getBytes(StandardCharsets.UTF_8)));
            session.transfer(CONFIG.server.getProxyAddressForTransfer(), CONFIG.server.getProxyPortForTransfer());
            return OK;
        });
    }
}
