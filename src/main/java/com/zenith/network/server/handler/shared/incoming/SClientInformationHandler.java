package com.zenith.network.server.handler.shared.incoming;

import com.zenith.network.codec.PacketHandler;
import com.zenith.network.server.ServerSession;
import org.geysermc.mcprotocollib.protocol.packet.common.serverbound.ServerboundClientInformationPacket;

import static com.zenith.Globals.CONFIG;

public class SClientInformationHandler implements PacketHandler<ServerboundClientInformationPacket, ServerSession> {
    public static final SClientInformationHandler INSTANCE = new SClientInformationHandler();
    @Override
    public ServerboundClientInformationPacket apply(final ServerboundClientInformationPacket packet, final ServerSession session) {
        session.getClientInfoCache()
            .setLocale(packet.getLanguage())
            .setRenderDistance(packet.getViewDistance())
            .setChatVisibility(packet.getChatVisibility())
            .setChatColors(packet.isChatColors())
            .setHandPreference(packet.getMainHand())
            .setTextFilteringEnabled(packet.isTextFilteringEnabled())
            .setAllowsListing(packet.isAllowsListing())
            .setParticleStatus(packet.getParticleStatus());
        // the server would see the settings change the moment someone takes over
        return CONFIG.client.privacy.steadyClientInfo ? null : packet;
    }
}
