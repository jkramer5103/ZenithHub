package com.zenith.network.client.handler.incoming;

import com.zenith.network.client.ClientSession;
import com.zenith.network.client.CoreRegistryData;
import com.zenith.network.codec.PacketHandler;
import org.geysermc.mcprotocollib.protocol.packet.configuration.clientbound.ClientboundSelectKnownPacks;
import org.geysermc.mcprotocollib.protocol.packet.configuration.serverbound.ServerboundSelectKnownPacks;

import java.util.Collections;
import java.util.List;

public class CSelectKnownPacksHandler implements PacketHandler<ClientboundSelectKnownPacks, ClientSession> {
    @Override
    public ClientboundSelectKnownPacks apply(final ClientboundSelectKnownPacks packet, final ClientSession session) {
        // like vanilla: claim the packs we have out of the ones the server offers. That's only the core pack of our
        // own version, whose data CRegistryDataHandler fills back in. Through ViaVersion the server offers its own
        // version's pack, which a vanilla client of our version doesn't have either.
        if (packet.getKnownPacks().contains(CoreRegistryData.CORE_PACK) && CoreRegistryData.available()) {
            session.send(new ServerboundSelectKnownPacks(List.of(CoreRegistryData.CORE_PACK)));
        } else {
            session.send(new ServerboundSelectKnownPacks(Collections.emptyList()));
        }
        return null;
    }
}
