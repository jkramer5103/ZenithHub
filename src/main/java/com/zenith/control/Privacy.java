package com.zenith.control;

import com.viaversion.nbt.io.MNBTIO;
import com.viaversion.nbt.tag.CompoundTag;
import com.zenith.network.codec.PacketHandler;
import com.zenith.network.server.ServerSession;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.gson.GsonComponentSerializer;
import org.geysermc.mcprotocollib.auth.GameProfile;
import org.geysermc.mcprotocollib.protocol.codec.NbtComponentSerializer;
import org.geysermc.mcprotocollib.protocol.data.game.PlayerListEntry;
import org.geysermc.mcprotocollib.protocol.data.game.PlayerListEntryAction;
import org.geysermc.mcprotocollib.protocol.packet.common.serverbound.ServerboundCustomPayloadPacket;
import org.geysermc.mcprotocollib.protocol.packet.ingame.clientbound.ClientboundPlayerInfoUpdatePacket;
import org.geysermc.mcprotocollib.protocol.packet.ingame.serverbound.ServerboundChatAckPacket;
import org.geysermc.mcprotocollib.protocol.packet.ingame.serverbound.level.ServerboundSignUpdatePacket;
import org.jspecify.annotations.Nullable;

import java.net.URI;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static com.zenith.Globals.CACHE;
import static com.zenith.Globals.CONFIG;
import static com.zenith.Globals.SERVER_LOG;

/**
 * Packet filters for players connected through Zenith, so their own client doesn't give the account away.
 * All of them except the chat ack one are opt-in (client.privacy.*).
 */
public final class Privacy {
    private Privacy() {}

    /** Zenith acknowledges signed chat itself; the player's own acks would count messages twice. */
    public static final PacketHandler<ServerboundChatAckPacket, ServerSession> DROP_CHAT_ACK = (packet, session) -> null;

    /** vanilla clients send no custom payloads while playing; modded ones register their channels */
    public static final PacketHandler<ServerboundCustomPayloadPacket, ServerSession> VANILLA_SPOOF = (packet, session) -> {
        if (!CONFIG.client.privacy.vanillaSpoof) return packet;
        SERVER_LOG.debug("Dropped custom payload from player: {}", packet.getChannel());
        return null;
    };

    /**
     * A sign with translation keys or keybinds in it, opened in the sign editor, makes the client send the text it
     * rendered. Send what an unmodded en_us client with default keybinds would have rendered instead.
     */
    public static final PacketHandler<ServerboundSignUpdatePacket, ServerSession> SIGN_UPDATE = (packet, session) -> {
        if (!CONFIG.client.privacy.translationProbeProtection) return packet;
        var original = signLines(packet.getX(), packet.getY(), packet.getZ(), packet.isFrontText());
        if (original == null) return packet;
        var lines = packet.getLines().toArray(String[]::new);
        boolean changed = false;
        for (int i = 0; i < lines.length && i < original.length; i++) {
            if (original[i] == null || !VanillaText.hasProbe(original[i])) continue;
            var vanilla = VanillaText.getString(original[i]);
            if (!vanilla.equals(lines[i])) {
                lines[i] = vanilla;
                changed = true;
            }
        }
        if (!changed) return packet;
        SERVER_LOG.info("Answered a sign translation probe like a vanilla client");
        return packet.withLines(List.of(lines));
    };

    /** Skins and capes from other hosts can log the IP of players connected through Zenith. */
    public static final PacketHandler<ClientboundPlayerInfoUpdatePacket, ServerSession> TRUSTED_SKINS = (packet, session) -> {
        if (!CONFIG.client.privacy.trustedSkinHostsOnly || !packet.getActions().contains(PlayerListEntryAction.ADD_PLAYER)) return packet;
        var entries = packet.getEntries();
        PlayerListEntry[] filtered = null;
        for (int i = 0; i < entries.length; i++) {
            var profile = entries[i].getProfile();
            if (profile == null || trustedTextures(profile)) continue;
            if (filtered == null) filtered = Arrays.copyOf(entries, entries.length);
            var stripped = new GameProfile(profile.getId(), profile.getName());
            var properties = new ArrayList<>(profile.getProperties());
            properties.removeIf(p -> "textures".equals(p.getName()));
            stripped.setProperties(properties);
            var e = entries[i];
            filtered[i] = new PlayerListEntry(e.getProfileId(), stripped, e.isListed(), e.getLatency(), e.getGameMode(),
                e.getDisplayName(), e.isShowHat(), e.getListOrder(), e.getSessionId(), e.getExpiresAt(), e.getPublicKey(), e.getKeySignature());
        }
        return filtered == null ? packet : new ClientboundPlayerInfoUpdatePacket(packet.getActions(), filtered);
    };

    private static boolean trustedTextures(final GameProfile profile) {
        if (profile.getProperty("textures") == null) return true;
        try {
            for (var texture : profile.getTextures(false).values()) {
                var host = URI.create(texture.getURL()).getHost();
                if (!"textures.minecraft.net".equalsIgnoreCase(host)) return false;
            }
            return true;
        } catch (final Exception e) {
            return false;
        }
    }

    private static Component @Nullable [] signLines(final int x, final int y, final int z, final boolean front) {
        var chunk = CACHE.getChunkCache().get(x >> 4, z >> 4);
        if (chunk == null) return null;
        for (var blockEntity : chunk.getBlockEntities()) {
            if (blockEntity.getX() != (x & 15) || blockEntity.getY() != y || blockEntity.getZ() != (z & 15)) continue;
            if (blockEntity.getNbt() == null) return null;
            try {
                var nbt = (CompoundTag) MNBTIO.read(blockEntity.getNbt());
                var text = nbt.getCompoundTag(front ? "front_text" : "back_text");
                var messages = text == null ? null : text.getListTag("messages");
                if (messages == null) return null;
                var lines = new Component[messages.size()];
                for (int i = 0; i < lines.length; i++) {
                    lines[i] = GsonComponentSerializer.gson().deserializeFromTree(NbtComponentSerializer.tagComponentToJson(messages.get(i)));
                }
                return lines;
            } catch (final Exception e) {
                SERVER_LOG.debug("Unable to read sign text at {} {} {}", x, y, z, e);
                return null;
            }
        }
        return null;
    }
}
