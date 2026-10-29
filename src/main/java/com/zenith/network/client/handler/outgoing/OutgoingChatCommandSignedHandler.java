package com.zenith.network.client.handler.outgoing;

import com.zenith.network.client.ClientSession;
import com.zenith.network.codec.PacketHandler;
import org.geysermc.mcprotocollib.protocol.packet.ingame.serverbound.ServerboundChatCommandSignedPacket;

import static com.zenith.Globals.CACHE;

public class OutgoingChatCommandSignedHandler implements PacketHandler<ServerboundChatCommandSignedPacket, ClientSession> {
    @Override
    public ServerboundChatCommandSignedPacket apply(final ServerboundChatCommandSignedPacket packet, final ClientSession session) {
        var lastChatTimestamp = CACHE.getChatCache().getLastChatTimestamp();
        var currentTime = System.currentTimeMillis();
        var packetTime = Math.max(lastChatTimestamp+1, currentTime);
        packet.setTimeStamp(packetTime);
        CACHE.getChatCache().setLastChatTimestamp(packetTime);
        if (CACHE.getChatCache().canUseChatSigning()) {
            var lastSeen = CACHE.getChatCache().getLastSeenMessages().generateAndApplyUpdate();
            var signedCommand = new ServerboundChatCommandSignedPacket(packet.getCommand(), packet.getTimeStamp(), OutgoingChatHandler.SALT.nextLong(), packet.getSignatures(), lastSeen.offset(), lastSeen.acknowledged());
            CACHE.getChatCache().getChatSession().sign(signedCommand, lastSeen.signatures());
            return signedCommand;
        }
        return packet;
    }
}
