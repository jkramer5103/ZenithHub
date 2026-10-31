package com.zenith.manager.instances;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;
import com.zenith.manager.state.State;
import com.zenith.manager.util.Json;

import org.jspecify.annotations.Nullable;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Settings layering: Zenith defaults → global → server → instance → forced managed values.
 */
public final class ConfigComposer {
    private ConfigComposer() {}

    public record Context(
        State.Instance instance,
        State.ServerProfile server,
        State.Account account,
        JsonObject globalSettings,
        State.ManagerSettings managerSettings,
        JoinLists joinLists,
        boolean cheatsDisabled,
        /** the admin's override for the account, or its proxy from the pool; null while neither applies */
        @Nullable PoolProxy poolProxy
    ) {
        public Context(State.Instance instance, State.ServerProfile server, State.Account account, JsonObject globalSettings,
                       State.ManagerSettings managerSettings, JoinLists joinLists, boolean cheatsDisabled) {
            this(instance, server, account, globalSettings, managerSettings, joinLists, cheatsDisabled, null);
        }
    }

    /**
     * The proxy forced for the instance's account: the admin's override, or else its proxy from the pool.
     * {@code host} is null while the pool has none free for it; type {@code DIRECT} means no proxy at all.
     */
    public record PoolProxy(String type, @Nullable String host, int port, String user, String password) {
        public static PoolProxy direct() {
            return new PoolProxy("DIRECT", "", 0, "", "");
        }

        public boolean missing() {
            return host == null;
        }

        public boolean isDirect() {
            return "DIRECT".equals(type);
        }
    }

    /** Zenith player entries derived from grants; the lobby is the only way in, so these replace Zenith's own lists. */
    public record JoinLists(JsonArray players, JsonArray spectators) {
        public static final JoinLists EMPTY = new JoinLists(new JsonArray(), new JsonArray());
    }

    /** proxyIP with the port, so Zenith's own transfers (spectator swap, /lobby) come back through the router. */
    public static String publicHostPort(State.ManagerSettings settings) {
        var address = settings.publicAddress.trim();
        // a bare IPv6 address or a host without a port
        if (address.startsWith("[") ? !address.contains("]:") : !address.contains(":")) return address + ":25565";
        return address;
    }

    /**
     * Paths {@link #forcedValues} forces for every instance, whatever its account, server or manager settings.
     * The settings pages leave them out. The outbound proxy and cheats are forced only sometimes.
     */
    public static final Set<String> ALWAYS_FORCED = Set.of(
        "server.bind.address", "server.bind.port", "server.upnp", "server.ping.lanBroadcast", "server.proxyIP",
        "server.connectionTestOnStart",
        "server.verifyUsers", "server.acceptTransfers", "server.onlyZenithTransfers", "server.enforceMatchingConnectingAddress",
        "server.loginRateLimiter.enabled", "server.extra.whitelist.enable", "server.extra.whitelist.autoAddClient",
        "server.extra.whitelist.whitelist", "server.spectator.whitelistEnabled", "server.spectator.whitelist",
        "client.server.address", "client.server.port", "client.autoConnect", "client.viaversion.enabled",
        "discord.accountOwnerRoleId", "discord.notificationMentionRoleId",
        "authentication.openBrowserOnLogin", "authentication.username", "authentication.accountType"
    );

    /** Values the manager always controls. Instances can't override these; in-game changes to them are discarded. */
    public static Map<String, JsonElement> forcedValues(Context ctx) {
        var forced = new LinkedHashMap<String, JsonElement>();
        var instance = ctx.instance();
        forced.put("server.bind.address", new JsonPrimitive("127.0.0.1"));
        forced.put("server.bind.port", new JsonPrimitive(instance.mcPort));
        forced.put("server.upnp", new JsonPrimitive(false));
        // instances are only reachable through the manager, never on the LAN
        forced.put("server.ping.lanBroadcast", new JsonPrimitive(false));
        forced.put("server.proxyIP", new JsonPrimitive(publicHostPort(ctx.managerSettings())));
        // would ping the lobby's address through an outside status service, which can't reach a LAN address and
        // learns the address; players reach instances through the lobby anyway
        forced.put("server.connectionTestOnStart", new JsonPrimitive(false));
        // players arrive from the lobby via transfer, verified, through the local router
        forced.put("server.verifyUsers", new JsonPrimitive(true));
        forced.put("server.acceptTransfers", new JsonPrimitive(true));
        forced.put("server.onlyZenithTransfers", new JsonPrimitive(false));
        forced.put("server.enforceMatchingConnectingAddress", new JsonPrimitive(false));
        forced.put("server.loginRateLimiter.enabled", new JsonPrimitive(false));
        // who may join comes from the instance's sharing settings
        forced.put("server.extra.whitelist.enable", new JsonPrimitive(true));
        forced.put("server.extra.whitelist.autoAddClient", new JsonPrimitive(false));
        forced.put("server.extra.whitelist.whitelist", ctx.joinLists().players().deepCopy());
        forced.put("server.spectator.whitelistEnabled", new JsonPrimitive(true));
        forced.put("server.spectator.whitelist", ctx.joinLists().spectators().deepCopy());
        forced.put("client.server.address", new JsonPrimitive(ctx.server().address));
        forced.put("client.server.port", new JsonPrimitive(ctx.server().port));
        // restore previous state: reconnect if it was connected when last observed
        forced.put("client.autoConnect", new JsonPrimitive(instance.lastConnected || instance.autostart == State.Autostart.ALWAYS));
        // without it the bot only speaks Zenith's native version and loops on "Outdated server" everywhere else
        forced.put("client.viaversion.enabled", new JsonPrimitive(true));
        // Zenith's notifications reach the manager's bot through the control API; any role mention means "ping the account owner"
        forced.put("discord.accountOwnerRoleId", new JsonPrimitive("1"));
        forced.put("discord.notificationMentionRoleId", new JsonPrimitive(""));
        forced.put("authentication.openBrowserOnLogin", new JsonPrimitive(false));
        var account = ctx.account();
        forced.put("authentication.username", new JsonPrimitive(account.username));
        if (account.type == State.AccountType.OFFLINE) {
            forced.put("authentication.accountType", new JsonPrimitive("offline"));
            forced.put("authentication.offlineUUID", account.uuid != null ? new JsonPrimitive(account.uuid) : JsonNull.INSTANCE);
        } else {
            forced.put("authentication.accountType", new JsonPrimitive("device_code"));
        }
        var pool = ctx.poolProxy();
        // no proxy can reach a server on the local network
        if (isLanAddress(ctx.server().address) || (pool != null && pool.isDirect())) forced.putAll(directForcedValues());
        else if (pool != null && !pool.missing()) forced.putAll(poolForcedValues(pool));
        else forced.putAll(proxyForcedValues(ctx.managerSettings(), ctx.globalSettings()));
        if (ctx.cheatsDisabled()) forced.putAll(Cheats.forcedValues());
        return forced;
    }

    // --- outbound proxy ---

    public static final String PROXY = "client.connectionProxy.";
    public static final List<String> PROXY_PATHS = List.of("enabled", "type", "host", "port", "user", "password").stream()
        .map(p -> PROXY + p).toList();
    /** Zenith's defaults (Config.Client.ConnectionProxy), for global layers that don't set a field. */
    private static final Map<String, JsonElement> PROXY_DEFAULTS = Map.of(
        PROXY + "enabled", new JsonPrimitive(false), PROXY + "type", new JsonPrimitive("SOCKS5"),
        PROXY + "host", new JsonPrimitive("127.0.0.1"), PROXY + "port", new JsonPrimitive(7890),
        PROXY + "user", new JsonPrimitive(""), PROXY + "password", new JsonPrimitive(""));

    /** The global proxy: the global layer's connection proxy. While it's on, every instance sends all its traffic through it. */
    public static JsonElement globalProxyValue(JsonObject globalSettings, String path) {
        var value = Json.get(globalSettings, path);
        return value != null ? value : PROXY_DEFAULTS.get(path);
    }

    public static boolean globalProxyOn(JsonObject globalSettings) {
        var enabled = globalProxyValue(globalSettings, PROXY + "enabled");
        return enabled.isJsonPrimitive() && enabled.getAsBoolean();
    }

    /**
     * Without own proxies allowed, every instance uses the global proxy exactly (or none while it's off).
     * With them allowed, users may change the proxy, but not turn it off while the global one is on.
     */
    public static Map<String, JsonElement> proxyForcedValues(State.ManagerSettings settings, JsonObject global) {
        var forced = new LinkedHashMap<String, JsonElement>();
        if (!settings.userProxiesAllowed) {
            for (var path : PROXY_PATHS) forced.put(path, globalProxyValue(global, path).deepCopy());
        } else if (globalProxyOn(global)) {
            forced.put(PROXY + "enabled", new JsonPrimitive(true));
        }
        return forced;
    }

    /** While the proxy pool is on, each account's own proxy from the pool replaces every other proxy setting. */
    public static Map<String, JsonElement> poolForcedValues(PoolProxy pool) {
        var forced = new LinkedHashMap<String, JsonElement>();
        forced.put(PROXY + "enabled", new JsonPrimitive(true));
        forced.put(PROXY + "type", new JsonPrimitive(pool.type()));
        forced.put(PROXY + "host", new JsonPrimitive(pool.host()));
        forced.put(PROXY + "port", new JsonPrimitive(pool.port()));
        forced.put(PROXY + "user", new JsonPrimitive(pool.user()));
        forced.put(PROXY + "password", new JsonPrimitive(pool.password()));
        return forced;
    }

    public static Map<String, JsonElement> directForcedValues() {
        var forced = new LinkedHashMap<String, JsonElement>();
        forced.put(PROXY + "enabled", new JsonPrimitive(false));
        return forced;
    }

    private static final Pattern IPV4 = Pattern.compile("\\d{1,3}(\\.\\d{1,3}){3}");
    private static final List<String> LAN_SUFFIXES = List.of(".local", ".lan", ".internal", ".home.arpa", ".localdomain");

    /**
     * A server on the local network: a private, loopback or link-local IP, {@code localhost}, a name without a dot
     * or a local-only domain. Names are never looked up, so deciding this sends nothing around the proxy.
     */
    public static boolean isLanAddress(@Nullable String address) {
        if (address == null) return false;
        var host = address.trim().toLowerCase(java.util.Locale.ROOT);
        if (host.startsWith("[") && host.endsWith("]")) host = host.substring(1, host.length() - 1);
        if (host.endsWith(".")) host = host.substring(0, host.length() - 1);
        if (host.isEmpty()) return false;
        if (IPV4.matcher(host).matches() || host.contains(":")) {
            try {
                // a literal, so no DNS lookup
                var ip = java.net.InetAddress.ofLiteral(host);
                if (ip.isLoopbackAddress() || ip.isSiteLocalAddress() || ip.isLinkLocalAddress()) return true;
                // IPv6 unique local addresses, fc00::/7
                return ip instanceof java.net.Inet6Address && (ip.getAddress()[0] & 0xFE) == 0xFC;
            } catch (IllegalArgumentException e) {
                return false;
            }
        }
        if (host.equals("localhost") || !host.contains(".")) return true;
        return LAN_SUFFIXES.stream().anyMatch(host::endsWith);
    }

    private static boolean sameProxyEndpoint(JsonObject config, JsonObject globalSettings) {
        for (var field : List.of("type", "host", "port")) {
            if (!globalProxyValue(globalSettings, PROXY + field).equals(Json.get(config, PROXY + field))) return false;
        }
        return true;
    }

    /** The global proxy's login, inherited by an instance that points at a different proxy: never sent there. */
    private static boolean leaksGlobalProxyLogin(JsonObject config, Context ctx, String path) {
        var global = Json.get(ctx.globalSettings(), path);
        if (global == null || global.equals(new JsonPrimitive(""))) return false;
        if (Json.get(ctx.server().settings, path) != null || Json.get(ctx.instance().settings, path) != null) return false;
        return !sameProxyEndpoint(config, ctx.globalSettings());
    }

    /** Layers below the instance: what the instance's overrides are relative to. */
    public static JsonObject baseLayers(JsonObject zenithDefaults, Context ctx) {
        var base = zenithDefaults != null ? zenithDefaults.deepCopy() : new JsonObject();
        Json.mergeInto(base, ctx.globalSettings());
        Json.mergeInto(base, ctx.server().settings);
        return base;
    }

    /** The config.json written for an instance before it starts. */
    public static JsonObject compose(JsonObject zenithDefaults, Context ctx) {
        var config = baseLayers(zenithDefaults, ctx);
        Json.mergeInto(config, ctx.instance().settings);
        for (var path : List.of(PROXY + "user", PROXY + "password")) {
            if (leaksGlobalProxyLogin(config, ctx, path)) Json.set(config, path, new JsonPrimitive(""));
        }
        forcedValues(ctx).forEach((path, value) -> Json.set(config, path, value));
        return config;
    }

    /**
     * Computes the instance's new overrides from the config.json Zenith saved,
     * so changes made in-game or by commands persist across restarts and layer changes.
     * Forced paths, and {@code stale} paths whose layer change never reached the instance, keep whatever
     * override the instance had before.
     */
    public static JsonObject extractInstanceOverrides(JsonObject zenithDefaults, Context ctx, JsonObject savedConfig, Set<String> stale) {
        var overrides = Json.diff(baseLayers(zenithDefaults, ctx), savedConfig);
        // blanked by compose, not by the user
        for (var path : List.of(PROXY + "user", PROXY + "password")) {
            if (new JsonPrimitive("").equals(Json.get(overrides, path)) && leaksGlobalProxyLogin(savedConfig, ctx, path)) {
                Json.remove(overrides, path);
            }
        }
        var previous = ctx.instance().settings;
        var keep = new java.util.HashSet<>(forcedValues(ctx).keySet());
        keep.addAll(stale);
        for (var path : keep) {
            Json.remove(overrides, path);
            var prev = Json.get(previous, path);
            if (prev != null) Json.set(overrides, path, prev.deepCopy());
        }
        return overrides;
    }

}
