package com.zenith.network.client;

import com.viaversion.nbt.io.MNBTIO;
import com.viaversion.nbt.mini.MNBT;
import org.geysermc.mcprotocollib.protocol.codec.MinecraftCodec;
import org.geysermc.mcprotocollib.protocol.data.game.KnownPack;
import org.geysermc.mcprotocollib.protocol.data.game.RegistryEntry;
import org.geysermc.mcprotocollib.protocol.packet.configuration.clientbound.ClientboundRegistryDataPacket;
import org.jspecify.annotations.Nullable;

import java.io.DataInputStream;
import java.util.HashMap;
import java.util.Map;
import java.util.zip.GZIPInputStream;

import static com.zenith.Globals.CLIENT_LOG;

/**
 * The registry entries of vanilla's built-in "core" data pack, captured from a vanilla server of Zenith's native
 * version. A vanilla client tells the server it already has this pack, and the server then leaves the data of those
 * entries out. Zenith answers the same way and fills the missing data in from here, so the cache (and every player
 * connecting through Zenith) still gets complete registries.
 */
public final class CoreRegistryData {
    public static final KnownPack CORE_PACK = new KnownPack("minecraft", "core", MinecraftCodec.CODEC.getMinecraftVersion());
    private static volatile @Nullable Map<String, Map<String, MNBT>> data;

    private CoreRegistryData() {}

    public static boolean available() {
        return load() != null;
    }

    /**
     * Fills in the entries the server left out, in place: the client session forwards the received packet object
     * to connected players, not the handler's return value. Returns false if one of them is unknown.
     */
    public static boolean fill(final ClientboundRegistryDataPacket packet) {
        var entries = packet.getEntries();
        if (entries.stream().allMatch(e -> e.getData() != null)) return true;
        var registries = load();
        var registry = registries == null ? null : registries.get(packet.getRegistry());
        for (int i = 0; i < entries.size(); i++) {
            var entry = entries.get(i);
            if (entry.getData() != null) continue;
            var known = registry == null ? null : registry.get(entry.getId());
            if (known == null) {
                CLIENT_LOG.error("Server left out registry data we don't have: {} {}", packet.getRegistry(), entry.getId());
                return false;
            }
            entries.set(i, new RegistryEntry(entry.getId(), known));
        }
        return true;
    }

    private static @Nullable Map<String, Map<String, MNBT>> load() {
        var loaded = data;
        if (loaded != null) return loaded.isEmpty() ? null : loaded;
        synchronized (CoreRegistryData.class) {
            if (data != null) return data.isEmpty() ? null : data;
            var result = new HashMap<String, Map<String, MNBT>>();
            try (var in = CoreRegistryData.class.getResourceAsStream("/mcdata/core-registries.bin.gz")) {
                if (in == null) throw new IllegalStateException("missing /mcdata/core-registries.bin.gz");
                var din = new DataInputStream(new GZIPInputStream(in));
                int registries = din.readInt();
                for (int i = 0; i < registries; i++) {
                    var name = din.readUTF();
                    int count = din.readInt();
                    var entries = new HashMap<String, MNBT>(count);
                    for (int j = 0; j < count; j++) entries.put(din.readUTF(), MNBTIO.read(din, false));
                    result.put(name, entries);
                }
            } catch (final Exception e) {
                CLIENT_LOG.error("Unable to load the core registry data, known packs are disabled", e);
                result.clear();
            }
            data = result;
            return result.isEmpty() ? null : result;
        }
    }
}
