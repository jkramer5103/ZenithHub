package com.zenith.network.client.handler.incoming;

import com.zenith.Proxy;
import com.zenith.cache.data.config.ResourcePack;
import com.zenith.network.client.ClientSession;
import com.zenith.network.client.ResourcePackDownloader;
import com.zenith.network.codec.PacketHandler;
import org.geysermc.mcprotocollib.protocol.data.game.ResourcePackStatus;
import org.geysermc.mcprotocollib.protocol.packet.common.clientbound.ClientboundResourcePackPushPacket;
import org.geysermc.mcprotocollib.protocol.packet.common.serverbound.ServerboundResourcePackPacket;

import static com.zenith.Globals.CACHE;
import static com.zenith.Globals.CONFIG;

public class ResourcePackPushHandler implements PacketHandler<ClientboundResourcePackPushPacket, ClientSession> {
    public static final ResourcePackPushHandler INSTANCE = new ResourcePackPushHandler();
    @Override
    public ClientboundResourcePackPushPacket apply(final ClientboundResourcePackPushPacket packet, final ClientSession session) {
        CACHE.getConfigurationCache().getResourcePacks().put(packet.getId(), new ResourcePack(packet.getId(), packet.getUrl(), packet.getHash(), packet.isRequired(), packet.getPrompt()));
        // a pack from a private network goes to Zenith, which refuses it, never to the player's client
        if (Proxy.getInstance().hasActivePlayer() && CONFIG.debug.passthroughResourcePacks
            && !ResourcePackDownloader.isBlockedLocalUrl(packet.getUrl(), session)) return packet;
        if (CONFIG.client.privacy.downloadResourcePacks) {
            ResourcePackDownloader.handle(packet, session);
        } else {
            session.sendAsync(new ServerboundResourcePackPacket(packet.getId(), ResourcePackStatus.ACCEPTED));
            session.sendAsync(new ServerboundResourcePackPacket(packet.getId(), ResourcePackStatus.DOWNLOADED));
            session.sendAsync(new ServerboundResourcePackPacket(packet.getId(), ResourcePackStatus.SUCCESSFULLY_LOADED));
        }
        return null;
    }
}
