package com.zenith.network.client.handler.postoutgoing;

import com.zenith.feature.spectator.SpectatorSync;
import com.zenith.network.client.ClientSession;
import com.zenith.network.codec.ClientEventLoopPacketHandler;
import org.geysermc.mcprotocollib.protocol.packet.ingame.serverbound.player.ServerboundMovePlayerPosPacket;

import static com.zenith.Globals.CACHE;

public class PostOutgoingPlayerPositionHandler implements ClientEventLoopPacketHandler<ServerboundMovePlayerPosPacket, ClientSession> {

    @Override
    public boolean applyAsync(ServerboundMovePlayerPosPacket packet, ClientSession session) {
        CACHE.getPlayerCache().recordMove(packet.getX(), packet.getY(), packet.getZ(), packet.isOnGround());
        SpectatorSync.syncPlayerPositionWithSpectators();
        return true;
    }
}
