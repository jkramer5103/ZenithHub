package com.zenith.manager.util;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.net.InetSocketAddress;
import java.net.ProxySelector;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.function.Supplier;
import java.util.regex.Pattern;

/**
 * Looks up Minecraft players by name, for player lists edited in the UI (whitelist, friends, ...).
 */
public final class MojangProfiles {
    private static final Pattern NAME = Pattern.compile("^[A-Za-z0-9_]{1,16}$");
    /** The global proxy's relay, or null to connect directly. */
    private static volatile Supplier<InetSocketAddress> proxy = () -> null;

    private MojangProfiles() {}

    public static void setProxy(Supplier<InetSocketAddress> relay) {
        proxy = relay;
    }

    /** A Zenith player list entry {username, uuid, lastRefreshed}, or null if no such player exists. */
    public static JsonObject lookup(String name) throws Exception {
        if (!NAME.matcher(name).matches()) throw new IllegalArgumentException("That isn't a valid Minecraft name");
        var request = HttpRequest.newBuilder(URI.create("https://api.mojang.com/users/profiles/minecraft/" + URLEncoder.encode(name, StandardCharsets.UTF_8)))
            .timeout(Duration.ofSeconds(10))
            .GET()
            .build();
        var builder = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5));
        var relay = proxy.get();
        if (relay != null) builder.proxy(ProxySelector.of(relay));
        HttpResponse<String> response;
        try (var http = builder.build()) {
            response = http.send(request, HttpResponse.BodyHandlers.ofString());
        }
        if (response.statusCode() == 404 || response.statusCode() == 204) return null;
        if (response.statusCode() != 200) throw new IllegalStateException("Mojang's profile service isn't responding, try again later");
        var json = JsonParser.parseString(response.body()).getAsJsonObject();
        var id = json.get("id").getAsString();
        var entry = new JsonObject();
        entry.addProperty("username", json.get("name").getAsString());
        entry.addProperty("uuid", id.replaceFirst("(\\w{8})(\\w{4})(\\w{4})(\\w{4})(\\w{12})", "$1-$2-$3-$4-$5"));
        entry.addProperty("lastRefreshed", System.currentTimeMillis() / 1000L);
        return entry;
    }
}
