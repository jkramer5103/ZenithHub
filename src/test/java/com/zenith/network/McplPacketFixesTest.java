package com.zenith.network;

import com.viaversion.nbt.io.MNBTIO;
import com.viaversion.nbt.tag.CompoundTag;
import com.zenith.network.codec.McplPacketFixes;
import io.netty.buffer.ByteBufUtil;
import io.netty.buffer.Unpooled;
import org.geysermc.mcprotocollib.network.codec.PacketDefinition;
import org.geysermc.mcprotocollib.protocol.codec.MinecraftCodec;
import org.geysermc.mcprotocollib.protocol.codec.MinecraftTypes;
import org.geysermc.mcprotocollib.protocol.data.ProtocolState;
import org.geysermc.mcprotocollib.protocol.packet.common.serverbound.ServerboundCustomClickActionPacket;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;

public class McplPacketFixesTest {
    @Test
    public void customClickActionRoundTrips() throws Exception {
        McplPacketFixes.apply();
        var payload = new CompoundTag();
        payload.putString("setting", "chat.public");
        payload.putBoolean("value", false);
        var mnbt = MNBTIO.write(payload, false);

        // as a vanilla client writes it: id, then the payload prefixed with its length
        var vanilla = Unpooled.buffer();
        MinecraftTypes.writeString(vanilla, "hugosmp:settings");
        MinecraftTypes.writeVarInt(vanilla, mnbt.getData().length);
        MinecraftTypes.writeMNBT(vanilla, mnbt);
        var expected = ByteBufUtil.getBytes(vanilla);

        var registry = MinecraftCodec.CODEC.getCodec(ProtocolState.GAME);
        int id = registry.getServerboundId(ServerboundCustomClickActionPacket.class);
        var packet = (ServerboundCustomClickActionPacket) registry.createServerboundPacket(id, Unpooled.wrappedBuffer(expected), null);
        var out = Unpooled.buffer();
        @SuppressWarnings("unchecked")
        var definition = (PacketDefinition<ServerboundCustomClickActionPacket>) registry.getServerboundDefinition(id);
        definition.getSerializer().serialize(out, packet);
        // MCProtocolLib pads the length varint, so compare what a server reads back rather than the bytes
        var reread = (ServerboundCustomClickActionPacket) registry.createServerboundPacket(id, out, null);
        assertEquals(0, out.readableBytes(), "bytes left over after the packet");
        assertEquals(packet.getId(), reread.getId());
        assertArrayEquals(mnbt.getData(), reread.getPayload().getData());
    }
}
