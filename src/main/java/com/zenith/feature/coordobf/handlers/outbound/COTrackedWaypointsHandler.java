package com.zenith.feature.coordobf.handlers.outbound;

import com.zenith.network.codec.PacketHandler;
import com.zenith.network.server.ServerSession;
import org.geysermc.mcprotocollib.protocol.packet.ingame.clientbound.level.ClientboundTrackedWaypointPacket;

public class COTrackedWaypointsHandler implements PacketHandler<ClientboundTrackedWaypointPacket, ServerSession> {
    @Override
    public ClientboundTrackedWaypointPacket apply(final ClientboundTrackedWaypointPacket packet, final ServerSession session) {
        return null;
    }
}
