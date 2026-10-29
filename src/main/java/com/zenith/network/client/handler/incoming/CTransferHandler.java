package com.zenith.network.client.handler.incoming;

import com.zenith.Proxy;
import com.zenith.network.client.ClientCookies;
import com.zenith.network.client.ClientSession;
import com.zenith.network.codec.PacketHandler;
import net.kyori.adventure.text.Component;
import org.geysermc.mcprotocollib.protocol.packet.common.clientbound.ClientboundTransferPacket;

import static com.zenith.Globals.CLIENT_LOG;
import static com.zenith.Globals.EXECUTOR;

public class CTransferHandler implements PacketHandler<ClientboundTransferPacket, ClientSession> {
    @Override
    public ClientboundTransferPacket apply(final ClientboundTransferPacket packet, final ClientSession session) {
        var reason = Component.text("Destination server requested transfer to: " + packet.getHost() + ":" + packet.getPort());
        // todo: should we send a transfer packet to all active connections so they can reconnect to us seamlessly?
        var connections = Proxy.getInstance().getActiveConnections().getArray();
        for (int i = 0; i < connections.length; i++) {
            var connection = connections[i];
            connection.disconnect(reason);
        }
        // the next connection keeps our cookies and announces itself as a transfer, like vanilla
        ClientCookies.markTransfer(packet.getHost(), packet.getPort());
        session.disconnect(reason);
        EXECUTOR.execute(() -> {
            try {
                Proxy.getInstance().connect(packet.getHost(), packet.getPort());
            } catch (final Exception e) {
                CLIENT_LOG.error("Error connecting to transfer destination: {}:{}", packet.getHost(), packet.getPort(), e);
            }

        });
        return null;
    }
}
