package com.zenith.network.codec;

import io.netty.buffer.ByteBuf;
import org.geysermc.mcprotocollib.network.codec.PacketDefinition;
import org.geysermc.mcprotocollib.network.codec.PacketSerializer;
import org.geysermc.mcprotocollib.protocol.codec.MinecraftCodec;
import org.geysermc.mcprotocollib.protocol.codec.MinecraftTypes;
import org.geysermc.mcprotocollib.protocol.data.ProtocolState;
import org.geysermc.mcprotocollib.protocol.packet.common.serverbound.ServerboundCustomClickActionPacket;

/**
 * Serializers for packets MCProtocolLib 26.3.0.5 writes wrongly. Remove once fixed upstream.
 */
public final class McplPacketFixes {
    private McplPacketFixes() {}

    public static void apply() {
        for (var state : new ProtocolState[] { ProtocolState.CONFIGURATION, ProtocolState.GAME }) {
            var registry = MinecraftCodec.CODEC.getCodec(state);
            final int id;
            try {
                id = registry.getServerboundId(ServerboundCustomClickActionPacket.class);
            } catch (final Exception e) {
                continue;
            }
            registry.registerServerbound(id, ServerboundCustomClickActionPacket.class, new CustomClickActionSerializer());
        }
    }

    /**
     * The library writes the length-prefixed payload and then the payload a second time, so servers kick with
     * "custom_click_action was larger than I expected" when a dialog button is clicked.
     */
    private static final class CustomClickActionSerializer implements PacketSerializer<ServerboundCustomClickActionPacket> {
        @Override
        public void serialize(final ByteBuf buf, final ServerboundCustomClickActionPacket packet) {
            MinecraftTypes.writeResourceLocation(buf, packet.getId());
            MinecraftTypes.writeLengthPrefixed(buf, 65536, packet.getPayload(), MinecraftTypes::writeMNBT);
        }

        @Override
        public ServerboundCustomClickActionPacket deserialize(final ByteBuf buf, final PacketDefinition<ServerboundCustomClickActionPacket> definition) {
            return new ServerboundCustomClickActionPacket(buf);
        }
    }
}
