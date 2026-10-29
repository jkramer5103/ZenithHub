package com.zenith.network.client.handler.incoming;

import com.zenith.network.client.ClientSession;
import com.zenith.network.client.CoreRegistryData;
import com.zenith.network.codec.PacketHandler;
import org.geysermc.mcprotocollib.protocol.packet.configuration.clientbound.ClientboundRegistryDataPacket;

import static com.zenith.Globals.CACHE;

public class CRegistryDataHandler implements PacketHandler<ClientboundRegistryDataPacket, ClientSession> {
    @Override
    public ClientboundRegistryDataPacket apply(final ClientboundRegistryDataPacket packet, final ClientSession session) {
        // entries of the core pack we claimed come without data
        if (!CoreRegistryData.fill(packet)) {
            session.disconnect("Server sent registry data Zenith can't complete");
            return null;
        }
        CACHE.getRegistriesCache().initialize(packet.getRegistry(), packet.getEntries());
        return packet;
    }
}
