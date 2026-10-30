package com.zenith.network.server.handler.spectator.outgoing;

import com.zenith.command.brigadier.McplCommandTreeMerger;
import com.zenith.command.impl.LobbyCommand;
import com.zenith.control.ManagedMode;
import com.zenith.network.codec.PacketHandler;
import com.zenith.network.server.ServerSession;
import org.geysermc.mcprotocollib.protocol.data.game.command.CommandNode;
import org.geysermc.mcprotocollib.protocol.packet.ingame.clientbound.ClientboundCommandsPacket;
import org.jspecify.annotations.Nullable;

import static com.zenith.Globals.*;

public class ClientCommandsSpectatorOutgoingHandler implements PacketHandler<ClientboundCommandsPacket, ServerSession> {
    @Override
    public ClientboundCommandsPacket apply(final ClientboundCommandsPacket packet, final ServerSession session) {
        if (CONFIG.inGameCommands.enable && CONFIG.inGameCommands.slashCommands && CONFIG.server.spectator.fullCommandsEnabled && CONFIG.server.spectator.fullCommandsAcceptSlashCommands) {
            if (CONFIG.server.spectator.fullCommandsRequireRegularWhitelist && !PLAYER_LISTS.getWhitelist().contains(session.getProfileCache().getProfile().getId())) {
                return lobbyOnly();
            }
            CommandNode[] zenithCommandNodes = COMMAND.getMcplCommandNodes();
            if (!CONFIG.server.spectator.fullCommandsServerCommands || packet.getFirstNodeIndex() != 0) {
                return new ClientboundCommandsPacket(
                    zenithCommandNodes,
                    0
                );
            } else {
                return new ClientboundCommandsPacket(
                    McplCommandTreeMerger.mergeCommandNodes(zenithCommandNodes, packet.getNodes()),
                    0
                );
            }
        }
        return lobbyOnly();
    }

    private static @Nullable ClientboundCommandsPacket lobbyOnly() {
        return ManagedMode.isManaged() ? new ClientboundCommandsPacket(LobbyCommand.commandTree(), 0) : null;
    }
}
