package com.zenith.manager.auth;

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
import java.time.Duration;
import java.util.HexFormat;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Login sessions, persisted so manager restarts (image updates) don't log everyone out.
 * Only SHA-256 hashes of the tokens are stored on disk.
 */
public final class Sessions {
    private static final Logger LOG = LoggerFactory.getLogger("Sessions");
    private static final long TTL_MS = Duration.ofDays(30).toMillis();

    private record Session(String userId, long expiresAt) {}

    private final Path file;
    private final Map<String, Session> sessions = new ConcurrentHashMap<>();

    public Sessions(Path file) {
        this.file = file;
        load();
    }

    public String create(String userId) {
        var token = Passwords.randomToken(32);
        sessions.put(hash(token), new Session(userId, System.currentTimeMillis() + TTL_MS));
        save();
        return token;
    }

    public String userId(String token) {
        if (token == null) return null;
        var key = hash(token);
        var session = sessions.get(key);
        if (session == null) return null;
        if (session.expiresAt() < System.currentTimeMillis()) {
            sessions.remove(key);
            save();
            return null;
        }
        return session.userId();
    }

    public void invalidate(String token) {
        if (token != null && sessions.remove(hash(token)) != null) save();
    }

    public void invalidateUser(String userId) {
        if (sessions.values().removeIf(s -> s.userId().equals(userId))) save();
    }

    /** Signs the user out everywhere except the session with {@code keepToken}. */
    public void invalidateUserExcept(String userId, String keepToken) {
        var keep = keepToken != null ? hash(keepToken) : null;
        if (sessions.entrySet().removeIf(e -> e.getValue().userId().equals(userId) && !e.getKey().equals(keep))) save();
    }

    private void load() {
        if (!Files.exists(file)) return;
        try {
            Map<String, Session> loaded = Json.GSON.fromJson(Files.readString(file), new TypeToken<Map<String, Session>>() {}.getType());
            var now = System.currentTimeMillis();
            if (loaded != null) loaded.forEach((k, v) -> {
                if (v != null && v.userId() != null && v.expiresAt() > now) sessions.put(k, v);
            });
        } catch (Exception e) {
            LOG.warn("Couldn't read sessions, everyone has to log in again", e);
        }
    }

    private synchronized void save() {
        try {
            SecureFiles.writeString(file, Json.COMPACT.toJson(sessions));
        } catch (Exception e) {
            LOG.warn("Couldn't save sessions", e);
        }
    }

    private static String hash(String token) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(token.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
