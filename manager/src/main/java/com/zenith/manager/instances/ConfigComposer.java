package com.zenith.manager.instances;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;
import com.zenith.manager.state.State;
import com.zenith.manager.util.Json;

import java.util.LinkedHashMap;
import java.util.Map;

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
        JoinLists joinLists
    ) {}

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
        // Zenith's own bot stays off; its notifications and chat relay reach the manager's bot through the control API
        forced.put("discord.enable", new JsonPrimitive(false));
        forced.put("discord.chatRelay.enable", new JsonPrimitive(false));
        // any role mention means "ping the account owner"
        forced.put("discord.accountOwnerRoleId", new JsonPrimitive("1"));
        forced.put("discord.notificationMentionRoleId", new JsonPrimitive(""));
        forced.put("interactiveTerminal.enable", new JsonPrimitive(false));
        forced.put("interactiveTerminal.logToDiscord", new JsonPrimitive(false));
        forced.put("authentication.openBrowserOnLogin", new JsonPrimitive(false));
        var account = ctx.account();
        forced.put("authentication.username", new JsonPrimitive(account.username));
        if (account.type == State.AccountType.OFFLINE) {
            forced.put("authentication.accountType", new JsonPrimitive("offline"));
            forced.put("authentication.offlineUUID", account.uuid != null ? new JsonPrimitive(account.uuid) : JsonNull.INSTANCE);
        } else {
            forced.put("authentication.accountType", new JsonPrimitive("device_code"));
        }
        if (!ctx.managerSettings().outboundProxiesEnabled) {
            forced.put("client.connectionProxy.enabled", new JsonPrimitive(false));
        }
        return forced;
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
        forcedValues(ctx).forEach((path, value) -> Json.set(config, path, value));
        return config;
    }

    /**
     * Computes the instance's new overrides from the config.json Zenith saved,
     * so changes made in-game or by commands persist across restarts and layer changes.
     * Forced paths keep whatever override the instance had before.
     */
    public static JsonObject extractInstanceOverrides(JsonObject zenithDefaults, Context ctx, JsonObject savedConfig) {
        var overrides = Json.diff(baseLayers(zenithDefaults, ctx), savedConfig);
        var previous = ctx.instance().settings;
        for (var path : forcedValues(ctx).keySet()) {
            Json.remove(overrides, path);
            var prev = Json.get(previous, path);
            if (prev != null) Json.set(overrides, path, prev.deepCopy());
        }
        return overrides;
    }

}
