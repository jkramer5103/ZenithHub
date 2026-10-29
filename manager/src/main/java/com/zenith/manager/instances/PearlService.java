package com.zenith.manager.instances;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.zenith.manager.ManagerConfig;
import com.zenith.manager.util.Json;
import com.zenith.manager.util.SecureFiles;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Files;
import java.nio.file.Path;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Stasis pearls: the list lives in the instance's PearlLoader settings, loading runs Zenith's pearlLoader command.
 *
 * Webhooks are secret URLs that load one pearl without logging in. Each person gets their own URL per pearl,
 * so it can be replaced without breaking anyone else's. They are kept in the secrets folder.
 */
public final class PearlService {
    private static final Logger LOG = LoggerFactory.getLogger("Pearls");
    public static final String PEARLS_PATH = "client.extra.pearlLoader.pearls";
    private static final long WEBHOOK_COOLDOWN_MS = 5_000;

    public record Pearl(String id, int x, int y, int z) { }

    /** A webhook: whoever holds {@code token} may load {@code pearl} as {@code userId}, while that user still may. */
    public record Webhook(String token, String userId, String instanceId, String pearl) { }

    public record LoadResult(boolean ok, String message) { }

    private final Supervisor supervisor;
    private final SettingsService settings;
    private final Path webhookFile;
    private final SecureRandom random = new SecureRandom();
    private final Map<String, Long> lastWebhookUse = new ConcurrentHashMap<>();
    private final List<Webhook> webhooks = new ArrayList<>();

    public PearlService(ManagerConfig config, Supervisor supervisor, SettingsService settings) {
        this.supervisor = supervisor;
        this.settings = settings;
        this.webhookFile = config.dataDir().resolve("manager").resolve("secrets").resolve("pearl-webhooks.json");
        loadWebhooks();
    }

    public List<Pearl> pearls(String instanceId) {
        var list = new ArrayList<Pearl>();
        var json = settings.effective(instanceId, PEARLS_PATH);
        if (json == null || !json.isJsonArray()) return list;
        for (var e : json.getAsJsonArray()) {
            if (!e.isJsonObject()) continue;
            var p = e.getAsJsonObject();
            try {
                list.add(new Pearl(p.get("id").getAsString(), p.get("x").getAsInt(), p.get("y").getAsInt(), p.get("z").getAsInt()));
            } catch (Exception ignored) {
                // a half-filled row from the settings editor
            }
        }
        return list;
    }

    public LoadResult load(String instanceId, String pearlId, String actor) {
        if (pearls(instanceId).stream().noneMatch(p -> p.id().equals(pearlId))) return new LoadResult(false, "There's no pearl called " + pearlId + ".");
        if (!supervisor.isConnected(instanceId)) return new LoadResult(false, "The bot isn't online. Connect it first.");
        try {
            var result = supervisor.command(instanceId, "pearlLoader load " + pearlId, actor);
            var embed = result.has("embed") && result.get("embed").isJsonObject() ? result.getAsJsonObject("embed") : new JsonObject();
            var title = str(embed, "title");
            if ("Loading Pearl".equals(title)) return new LoadResult(true, "Loading " + pearlId + "'s pearl.");
            var description = str(embed, "description");
            if ("Player is controlling".equals(description)) return new LoadResult(false, "Someone is playing on the account right now, so the bot can't move.");
            if ("Bot is not online".equals(description)) return new LoadResult(false, "The bot isn't online. Connect it first.");
            return new LoadResult(false, description != null ? description : title != null ? title : "The pearl couldn't be loaded.");
        } catch (Exception e) {
            return new LoadResult(false, e.getMessage() != null ? e.getMessage() : "The pearl couldn't be loaded.");
        }
    }

    // --- webhooks ---

    public synchronized Webhook webhook(String userId, String instanceId, String pearl, boolean replace) {
        if (replace) webhooks.removeIf(w -> matches(w, userId, instanceId, pearl));
        var existing = webhooks.stream().filter(w -> matches(w, userId, instanceId, pearl)).findFirst();
        if (existing.isPresent()) return existing.get();
        var bytes = new byte[24];
        random.nextBytes(bytes);
        var webhook = new Webhook(Base64.getUrlEncoder().withoutPadding().encodeToString(bytes), userId, instanceId, pearl);
        webhooks.add(webhook);
        saveWebhooks();
        return webhook;
    }

    public synchronized boolean hasWebhook(String userId, String instanceId, String pearl) {
        return webhooks.stream().anyMatch(w -> matches(w, userId, instanceId, pearl));
    }

    public synchronized void deleteWebhook(String userId, String instanceId, String pearl) {
        if (webhooks.removeIf(w -> matches(w, userId, instanceId, pearl))) saveWebhooks();
    }

    /** Drops every webhook of an instance or of a user (pass null for the other), e.g. when either is deleted. */
    public synchronized void forget(String instanceId, String userId) {
        if (webhooks.removeIf(w -> w.instanceId().equals(instanceId) || w.userId().equals(userId))) saveWebhooks();
    }

    public synchronized Webhook resolve(String token) {
        if (token == null) return null;
        for (var w : webhooks) {
            if (java.security.MessageDigest.isEqual(w.token().getBytes(), token.getBytes())) return w;
        }
        return null;
    }

    /** Stops a webhook from being hammered; true if it may run now. */
    public boolean webhookCooldown(String token) {
        long now = System.currentTimeMillis();
        var last = lastWebhookUse.put(token, now);
        if (last != null && now - last < WEBHOOK_COOLDOWN_MS) {
            lastWebhookUse.put(token, last);
            return false;
        }
        return true;
    }

    private static String str(JsonObject json, String key) {
        var e = json.get(key);
        return e != null && e.isJsonPrimitive() ? e.getAsString() : null;
    }

    private static boolean matches(Webhook w, String userId, String instanceId, String pearl) {
        return w.userId().equals(userId) && w.instanceId().equals(instanceId) && w.pearl().equals(pearl);
    }

    private void loadWebhooks() {
        if (!Files.exists(webhookFile)) return;
        try {
            for (var e : JsonParser.parseString(Files.readString(webhookFile)).getAsJsonArray()) {
                var o = e.getAsJsonObject();
                webhooks.add(new Webhook(o.get("token").getAsString(), o.get("userId").getAsString(),
                    o.get("instanceId").getAsString(), o.get("pearl").getAsString()));
            }
        } catch (Exception e) {
            LOG.warn("Couldn't read pearl webhooks: {}", e.getMessage());
        }
    }

    private void saveWebhooks() {
        var array = new JsonArray();
        for (var w : webhooks) {
            var o = new JsonObject();
            o.addProperty("token", w.token());
            o.addProperty("userId", w.userId());
            o.addProperty("instanceId", w.instanceId());
            o.addProperty("pearl", w.pearl());
            array.add(o);
        }
        try {
            SecureFiles.writeString(webhookFile, Json.GSON.toJson(array));
        } catch (Exception e) {
            LOG.warn("Couldn't save pearl webhooks: {}", e.getMessage());
        }
    }
}
