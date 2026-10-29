package com.zenith.network.client.handler.incoming;

import com.zenith.network.client.ClientCookies;
import com.zenith.network.client.ClientSession;
import com.zenith.network.codec.PacketHandler;
import org.geysermc.mcprotocollib.protocol.packet.common.clientbound.ClientboundCookieRequestPacket;
import org.geysermc.mcprotocollib.protocol.packet.common.clientbound.ServerboundCookieResponsePacket;

public class CCookieRequestHandler implements PacketHandler<ClientboundCookieRequestPacket, ClientSession> {
    @Override
    public ClientboundCookieRequestPacket apply(final ClientboundCookieRequestPacket packet, final ClientSession session) {
        // vanilla always answers, with an empty payload for cookies it doesn't have
        session.send(new ServerboundCookieResponsePacket(packet.getKey(), ClientCookies.get(packet.getKey())));
        return null;
    }
}
