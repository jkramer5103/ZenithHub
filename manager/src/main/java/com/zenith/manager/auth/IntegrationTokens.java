package com.zenith.manager.auth;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.reflect.TypeToken;
import com.zenith.manager.util.Json;
import com.zenith.manager.util.SecureFiles;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Base64;
import java.util.EnumSet;
import java.util.HexFormat;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.function.LongSupplier;

/**
 * Integration tokens: let another program (like Blockbot) use the API as a person, without their password.
 *
 * <p>A token acts as the person who made it, limited to the instances and capabilities they ticked. It never
 * exceeds what that person may do right now, so losing access to an instance takes it from the token as well.
 * Only a SHA-256 hash is kept (the token is 256 random bits), in the secrets folder; the token itself is shown once.
 */
public final class IntegrationTokens {
    private static final Logger LOG = LoggerFactory.getLogger("Integrations");
    public static final String PREFIX = "zh_";
    /** What a token can be allowed to do. Watching or joining in-game and sharing need a person. */
    public static final Set<Capability> GRANTABLE = EnumSet.of(Capability.VIEW, Capability.CHAT, Capability.PEARLS, Capability.PLAY,
        Capability.CONTROL, Capability.COMMANDS, Capability.SETTINGS);
    public static final int MAX_PER_USER = 20;
    private static final long SAVE_LAST_USED_EVERY_MS = 60_000;

    /** One token as it's stored. {@code instances} is ignored when {@code allInstances} is set. */
    public static final class Token {
        public String id;
        public String userId;
        public String name;
        public String hash;
        public boolean allInstances;
        public Set<String> instances = new LinkedHashSet<>();
        public Set<Capability> capabilities = EnumSet.noneOf(Capability.class);
        public long created;
        public long lastUsed;

        public boolean covers(String instanceId) {
            return allInstances || instances.contains(instanceId);
        }

        public JsonObject toJson() {
            var json = new JsonObject();
            json.addProperty("id", id);
            json.addProperty("name", name);
            json.addProperty("allInstances", allInstances);
            var list = new JsonArray();
            instances.forEach(list::add);
            json.add("instances", list);
            var caps = new JsonArray();
            capabilities.forEach(c -> caps.add(c.name()));
            json.add("capabilities", caps);
            json.addProperty("created", created);
            if (lastUsed > 0) json.addProperty("lastUsed", lastUsed);
            return json;
        }
    }

    /** A new token: {@code secret} is the only time the plain token exists outside the caller. */
    public record Created(Token token, String secret) { }

    private final Path file;
    private final LongSupplier clock;
    private final SecureRandom random = new SecureRandom();
    private final List<Token> tokens = new ArrayList<>();
    private long lastSaved;

    public IntegrationTokens(Path file) {
        this(file, System::currentTimeMillis);
    }

    IntegrationTokens(Path file, LongSupplier clock) {
        this.file = file;
        this.clock = clock;
        load();
    }

    public synchronized Created create(String userId, String name, boolean allInstances, Set<String> instances, Set<Capability> capabilities) {
        name = name == null ? "" : name.strip();
        if (name.isEmpty() || name.length() > 40) throw new IllegalArgumentException("Give it a name of 1-40 characters");
        if (!allInstances && (instances == null || instances.isEmpty())) throw new IllegalArgumentException("Pick at least one instance");
        if (capabilities == null || !GRANTABLE.containsAll(capabilities)) throw new IllegalArgumentException("A token can't get that permission");
        if (tokens.stream().filter(t -> t.userId.equals(userId)).count() >= MAX_PER_USER) {
            throw new IllegalArgumentException("You have " + MAX_PER_USER + " tokens already. Delete one first.");
        }
        var bytes = new byte[32];
        random.nextBytes(bytes);
        var secret = PREFIX + Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
        var idBytes = new byte[6];
        random.nextBytes(idBytes);

        var token = new Token();
        token.id = HexFormat.of().formatHex(idBytes);
        token.userId = userId;
        token.name = name;
        token.hash = hash(secret);
        token.allInstances = allInstances;
        if (!allInstances) token.instances = new LinkedHashSet<>(instances);
        token.capabilities = EnumSet.of(Capability.VIEW);
        token.capabilities.addAll(capabilities);
        token.created = clock.getAsLong();
        tokens.add(token);
        save();
        return new Created(token, secret);
    }

    public synchronized List<Token> list(String userId) {
        return tokens.stream().filter(t -> t.userId.equals(userId)).toList();
    }

    /** Deletes one of the person's tokens; false if they have no such token. */
    public synchronized boolean revoke(String userId, String id) {
        boolean removed = tokens.removeIf(t -> t.userId.equals(userId) && t.id.equals(id));
        if (removed) save();
        return removed;
    }

    /** An instance was deleted (drop it from every token) or a user was (drop their tokens). Pass null for the other. */
    public synchronized void forget(String instanceId, String userId) {
        boolean changed = userId != null && tokens.removeIf(t -> t.userId.equals(userId));
        if (instanceId != null) {
            for (var t : tokens) changed |= t.instances.remove(instanceId);
        }
        if (changed) save();
    }

    /** The token for a plain secret from an Authorization header, or null. Notes when it was used. */
    public synchronized Token resolve(String secret) {
        if (secret == null || !secret.startsWith(PREFIX) || secret.length() > 100) return null;
        var h = hash(secret).getBytes(StandardCharsets.US_ASCII);
        Token found = null;
        for (var t : tokens) {
            if (MessageDigest.isEqual(t.hash.getBytes(StandardCharsets.US_ASCII), h)) found = t;
        }
        if (found != null) {
            long now = clock.getAsLong();
            found.lastUsed = now;
            if (now - lastSaved >= SAVE_LAST_USED_EVERY_MS) save();
        }
        return found;
    }

    /** Whether the token still exists, e.g. for a WebSocket opened with it. */
    public synchronized boolean exists(String id) {
        return tokens.stream().anyMatch(t -> t.id.equals(id));
    }

    /**
     * What a request made with {@code token} may do on an instance: the person's own capabilities there,
     * cut down to the token's. Nothing for instances the token doesn't cover.
     */
    public static Set<Capability> scope(Token token, String instanceId, Set<Capability> personCaps) {
        var caps = EnumSet.noneOf(Capability.class);
        if (token == null || !token.covers(instanceId)) return caps;
        caps.addAll(personCaps);
        caps.retainAll(token.capabilities);
        return caps;
    }

    static String hash(String secret) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(secret.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private void load() {
        if (!Files.exists(file)) return;
        try {
            List<Token> loaded = Json.GSON.fromJson(Files.readString(file), new TypeToken<List<Token>>() { }.getType());
            if (loaded == null) return;
            for (var t : loaded) {
                if (t.id == null || t.userId == null || t.hash == null) continue;
                if (t.instances == null) t.instances = new LinkedHashSet<>();
                var caps = EnumSet.noneOf(Capability.class);
                if (t.capabilities != null) t.capabilities.stream().filter(c -> c != null && GRANTABLE.contains(c)).forEach(caps::add);
                caps.add(Capability.VIEW);
                t.capabilities = caps;
                tokens.add(t);
            }
        } catch (Exception e) {
            LOG.warn("Couldn't read integration tokens: {}", e.getMessage());
        }
    }

    private void save() {
        lastSaved = clock.getAsLong();
        try {
            SecureFiles.writeString(file, Json.GSON.toJson(tokens));
        } catch (Exception e) {
            LOG.warn("Couldn't save integration tokens: {}", e.getMessage());
        }
    }
}
