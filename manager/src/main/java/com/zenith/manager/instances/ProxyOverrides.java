package com.zenith.manager.instances;

import com.google.gson.JsonObject;
import com.google.gson.reflect.TypeToken;
import com.zenith.manager.ManagerConfig;
import com.zenith.manager.util.Json;
import com.zenith.manager.util.SecureFiles;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * The admin's proxy for an account, over the proxy pool, the global proxy and users' own: a proxy of its own,
 * or none at all. They hold proxy logins, so they live in a secret file, not in state.json.
 */
public final class ProxyOverrides {
    private static final Logger LOG = LoggerFactory.getLogger("ProxyOverrides");
    private static final Pattern HOST = Pattern.compile("[A-Za-z0-9.\\-]{1,253}|\\[[0-9A-Fa-f:.]{2,45}]");

    /** {@code type} is SOCKS5, SOCKS4, HTTP or DIRECT. */
    public record Override(String type, String host, int port, String user, String password) {
        ConfigComposer.PoolProxy proxy() {
            return type.equals("DIRECT") ? ConfigComposer.PoolProxy.direct() : new ConfigComposer.PoolProxy(type, host, port, user, password);
        }
    }

    private final Path file;
    private volatile Map<String, Override> overrides = Map.of();

    public ProxyOverrides(ManagerConfig config) {
        this(config.dataDir().resolve("manager").resolve("secrets").resolve("proxy-overrides.json"));
    }

    public ProxyOverrides(Path file) {
        this.file = file;
        try {
            if (Files.exists(file)) {
                Map<String, Override> read = Json.GSON.fromJson(Files.readString(file), new TypeToken<Map<String, Override>>() {}.getType());
                if (read != null) overrides = Map.copyOf(read);
            }
        } catch (Exception e) {
            LOG.warn("Couldn't read the proxy overrides", e);
        }
    }

    public ConfigComposer.@Nullable PoolProxy forAccount(String accountId) {
        var o = overrides.get(accountId);
        return o != null ? o.proxy() : null;
    }

    public @Nullable Override get(String accountId) {
        return overrides.get(accountId);
    }

    /** Checks an override from the web UI; a blank password keeps the saved one for the same proxy. */
    public Override parse(String accountId, JsonObject body) {
        var type = string(body, "type").toUpperCase(java.util.Locale.ROOT);
        Override override;
        if (type.equals("DIRECT")) {
            override = new Override("DIRECT", "", 0, "", "");
        } else {
            if (!ProxyPool.TYPES.contains(type)) throw new IllegalArgumentException("Unknown proxy type " + type);
            var host = string(body, "host").trim();
            if (!HOST.matcher(host).matches()) throw new IllegalArgumentException("Invalid host");
            int port;
            try {
                port = body.get("port").getAsInt();
            } catch (RuntimeException e) {
                throw new IllegalArgumentException("Invalid port");
            }
            if (port < 1 || port > 65535) throw new IllegalArgumentException("Invalid port");
            var user = string(body, "user");
            var password = string(body, "password");
            if (user.length() > 255 || password.length() > 255 || (user + password).chars().anyMatch(Character::isISOControl)) {
                throw new IllegalArgumentException("Invalid login");
            }
            var old = overrides.get(accountId);
            if (password.isEmpty() && old != null && old.host().equals(host) && old.port() == port && old.user().equals(user)) {
                password = old.password();
            }
            override = new Override(type, host, port, user, password);
        }
        return override;
    }

    public synchronized void put(String accountId, Override override) throws IOException {
        var next = new LinkedHashMap<>(overrides);
        next.put(accountId, override);
        save(next);
    }

    public synchronized void clear(String accountId) throws IOException {
        if (!overrides.containsKey(accountId)) return;
        var next = new LinkedHashMap<>(overrides);
        next.remove(accountId);
        save(next);
    }

    /** Drops overrides of deleted accounts. */
    public synchronized void retain(Set<String> accountIds) throws IOException {
        if (accountIds.containsAll(overrides.keySet())) return;
        var next = new LinkedHashMap<>(overrides);
        next.keySet().retainAll(accountIds);
        save(next);
    }

    /** For the admin: never the password. */
    public @Nullable JsonObject view(String accountId) {
        var o = overrides.get(accountId);
        if (o == null) return null;
        var json = new JsonObject();
        json.addProperty("type", o.type());
        if (!o.type().equals("DIRECT")) {
            json.addProperty("host", o.host());
            json.addProperty("port", o.port());
            json.addProperty("user", o.user());
            json.addProperty("hasPassword", !o.password().isEmpty());
        }
        return json;
    }

    private void save(Map<String, Override> next) throws IOException {
        Files.createDirectories(file.getParent());
        SecureFiles.writeString(file, Json.GSON.toJson(next));
        overrides = Map.copyOf(next);
    }

    private static String string(JsonObject body, String key) {
        var value = body.get(key);
        return value != null && value.isJsonPrimitive() ? value.getAsString() : "";
    }
}
