package com.zenith.manager.instances;

import com.google.gson.JsonObject;
import com.zenith.manager.ManagerConfig;
import com.zenith.manager.state.State;
import com.zenith.manager.state.StateStore;
import com.zenith.manager.util.Json;
import com.zenith.manager.util.ProxyRelay;
import org.jspecify.annotations.Nullable;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.InetSocketAddress;
import java.util.Comparator;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The proxy the manager's own requests use, so Microsoft, Xbox and Mojang see the same IP as the server does.
 * An account's sign-in and token refreshes go through the proxy of its oldest instance; requests that
 * belong to no account (new sign-ins, player lookups) go through the global proxy while it's on.
 * Each distinct proxy gets a {@link ProxyRelay} on 127.0.0.1.
 */
public final class OutboundProxies {
    private final ManagerConfig config;
    private final StateStore store;
    private final Supervisor supervisor;
    private final Map<ProxyRelay.Upstream, ProxyRelay> relays = new ConcurrentHashMap<>();

    public OutboundProxies(ManagerConfig config, StateStore store, Supervisor supervisor) {
        this.config = config;
        this.store = store;
        this.supervisor = supervisor;
    }

    /** The relay for an account's requests, or null to connect directly. {@code accountId} may be null for a new account. */
    public @Nullable InetSocketAddress forAccount(@Nullable String accountId) {
        var instanceId = accountId == null ? null : store.read(s -> s.instances.stream()
            .filter(i -> i.accountId.equals(accountId))
            .min(Comparator.comparingLong((State.Instance i) -> i.createdAt))
            .map(i -> i.id).orElse(null));
        if (instanceId == null) return global();
        ConfigComposer.Context ctx;
        try {
            ctx = supervisor.context(instanceId);
        } catch (IllegalStateException e) {
            // its server was deleted, so it can't run
            return global();
        }
        if (ctx.poolProxy() != null && ctx.poolProxy().missing()) {
            throw new IllegalStateException("The proxy pool has no free proxy for this account");
        }
        return relay(upstream(ConfigComposer.compose(supervisor.zenithDefaults(config.instanceDir(instanceId)), ctx)));
    }

    /** The global proxy's relay while it's on, otherwise null. */
    public @Nullable InetSocketAddress global() {
        var global = store.read(s -> s.globalSettings.deepCopy());
        if (!ConfigComposer.globalProxyOn(global)) return null;
        var values = new JsonObject();
        for (var path : ConfigComposer.PROXY_PATHS) Json.set(values, path, ConfigComposer.globalProxyValue(global, path));
        return relay(upstream(values));
    }

    private static ProxyRelay.@Nullable Upstream upstream(JsonObject config) {
        var enabled = Json.get(config, ConfigComposer.PROXY + "enabled");
        if (enabled == null || !enabled.getAsBoolean()) return null;
        return new ProxyRelay.Upstream(
            ProxyRelay.Type.valueOf(string(config, "type", "SOCKS5")),
            string(config, "host", ""),
            Json.get(config, ConfigComposer.PROXY + "port").getAsInt(),
            string(config, "user", ""),
            string(config, "password", ""));
    }

    private static String string(JsonObject config, String field, String fallback) {
        var value = Json.get(config, ConfigComposer.PROXY + field);
        return value != null && value.isJsonPrimitive() ? value.getAsString() : fallback;
    }

    private @Nullable InetSocketAddress relay(ProxyRelay.@Nullable Upstream upstream) {
        if (upstream == null) return null;
        return relays.computeIfAbsent(upstream, up -> {
            try {
                return ProxyRelay.start(() -> up);
            } catch (IOException e) {
                // never fall back to a direct connection
                throw new UncheckedIOException("Couldn't start the proxy relay", e);
            }
        }).address();
    }
}
