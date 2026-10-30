package com.zenith.network.client.handler.incoming;

import com.zenith.network.client.ClientSession;
import com.zenith.network.codec.ClientEventLoopPacketHandler;
import org.geysermc.mcprotocollib.protocol.packet.ingame.clientbound.level.ClientboundTrackedWaypointPacket;

import static com.zenith.Globals.CACHE;

public class TrackedWaypointHandler implements ClientEventLoopPacketHandler<ClientboundTrackedWaypointPacket, ClientSession> {
    @Override
    public boolean applyAsync(final ClientboundTrackedWaypointPacket packet, final ClientSession session) {
        CACHE.getWaypointCache().updateWaypoints(packet);
        return true;
    }
}
