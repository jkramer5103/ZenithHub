package com.zenith.network.server.handler.spectator.incoming.movement;

import com.zenith.Proxy;
import com.zenith.cache.data.entity.Entity;
import com.zenith.feature.spectator.SpectatorSync;
import com.zenith.network.codec.PacketHandler;
import com.zenith.network.server.ServerSession;
import org.geysermc.mcprotocollib.protocol.packet.ingame.clientbound.ClientboundSetCameraPacket;
import org.geysermc.mcprotocollib.protocol.packet.ingame.serverbound.level.ServerboundPlayerInputPacket;

public class PlayerInputSpectatorHandler implements PacketHandler<ServerboundPlayerInputPacket, ServerSession> {
    @Override
    public ServerboundPlayerInputPacket apply(final ServerboundPlayerInputPacket packet, final ServerSession session) {
        var sneak = packet.isShift();
        var cacheSneak = session.getSpectatorPlayerCache().isSneaking();
        if (sneak != cacheSneak) {
            session.getSpectatorPlayerCache().setSneaking(sneak);
            Entity cameraTarget = session.getCameraTarget();
            if (cameraTarget != null) {
                if (sneak) {
                    session.setCameraTarget(null);
                    session.send(new ClientboundSetCameraPacket(session.getSpectatorSelfEntityId()));
                    SpectatorSync.syncSpectatorPositionToEntity(session, cameraTarget);
                }
            } else {
                if (!sneak) {
                    session.getSoundPacket().ifPresent(p -> {
                        var connections = Proxy.getInstance().getActiveConnections().getArray();
                        for (int i = 0; i < connections.length; i++) {
                            var connection = connections[i];
                            connection.send(p);
                        }
                    });
                }
            }
        }
        return null;
    }
}
