package com.zenith.manager.lobby;

import com.google.gson.JsonObject;
import com.google.gson.reflect.TypeToken;
import com.zenith.manager.util.Json;
import com.zenith.manager.util.MojangProfiles;
import com.zenith.manager.util.SecureFiles;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;

/**
 * Minecraft name → UUID, for the whitelists the manager hands to instances (Zenith matches players by UUID).
 * Filled from verified lobby logins and from Mojang lookups.
 */
public final class PlayerDirectory {
    private static final Logger LOG = LoggerFactory.getLogger("Players");

    public record Player(String name, String uuid) {}

    private final Path file;
    private final Map<String, Player> byName = new ConcurrentHashMap<>();
    private final Set<String> pending = ConcurrentHashMap.newKeySet();
    private volatile Consumer<String> onLearned = name -> {};

    public PlayerDirectory(Path file) {
        this.file = file;
        try {
            if (Files.exists(file)) {
                Map<String, Player> loaded = Json.GSON.fromJson(Files.readString(file), new TypeToken<Map<String, Player>>() {}.getType());
                if (loaded != null) {
                    // older versions cached offline-mode UUIDs from an offline lobby; Mojang's are never name-based
                    loaded.values().removeIf(p -> UUID.fromString(p.uuid()).version() == 3);
                    byName.putAll(loaded);
                }
            }
        } catch (Exception e) {
            LOG.warn("Couldn't read {}: {}", file, e.getMessage());
        }
    }

    /** Called with a name once its UUID becomes known. */
    public void onLearned(Consumer<String> listener) {
        this.onLearned = listener;
    }

    /** The cached player, or null; unknown names are looked up in the background. */
    public Player get(String name) {
        var key = name.toLowerCase(Locale.ROOT);
        var player = byName.get(key);
        if (player == null && pending.add(key)) {
            Thread.ofVirtual().name("mojang-lookup").start(() -> lookup(name, key));
        }
        return player;
    }

    /** A login the lobby verified with Mojang; also catches name changes. */
    public void remember(String name, String uuid) {
        var key = name.toLowerCase(Locale.ROOT);
        var previous = byName.put(key, new Player(name, uuid));
        if (previous == null || !previous.equals(new Player(name, uuid))) {
            save();
            onLearned.accept(name);
        }
    }

    /** A Zenith PlayerEntry {username, uuid, lastRefreshed}. */
    public static JsonObject entry(Player p) {
        var json = new JsonObject();
        json.addProperty("username", p.name());
        json.addProperty("uuid", p.uuid());
        json.addProperty("lastRefreshed", System.currentTimeMillis() / 1000L);
        return json;
    }

    private void lookup(String name, String key) {
        try {
            var entry = MojangProfiles.lookup(name);
            if (entry == null) {
                LOG.info("No Minecraft account named {}", name);
                return;
            }
            remember(entry.get("username").getAsString(), entry.get("uuid").getAsString());
        } catch (Exception e) {
            LOG.warn("Couldn't look up {}: {}", name, e.getMessage());
            // retried on the next request
            pending.remove(key);
        }
    }

    private synchronized void save() {
        try {
            SecureFiles.writeString(file, Json.GSON.toJson(new LinkedHashMap<>(byName)));
        } catch (IOException e) {
            LOG.warn("Couldn't save {}: {}", file, e.getMessage());
        }
    }
}
