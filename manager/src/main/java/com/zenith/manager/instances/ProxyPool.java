package com.zenith.manager.instances;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.zenith.manager.ManagerConfig;
import com.zenith.manager.state.State;
import com.zenith.manager.state.StateStore;
import com.zenith.manager.util.SecureFiles;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.ProxySelector;
import java.net.URI;
import java.net.URLDecoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.Supplier;

/**
 * Proxy pooling: a list of proxies downloaded from a provider (Webshare and the like), and one of them for each
 * account, so every account connects from its own IP. An account keeps its proxy for as long as the list has it.
 * The list URL holds the provider's API key and the list holds the proxies' logins, so both are secret files.
 */
public final class ProxyPool {
    private static final Logger LOG = LoggerFactory.getLogger("ProxyPool");
    /** Providers replace dead proxies in the list; reading it this often notices that. */
    private static final Duration REFRESH_EVERY = Duration.ofHours(6);
    public static final Set<String> TYPES = Set.of("SOCKS5", "SOCKS4", "HTTP");

    public record Proxy(String host, int port, String user, String password) {
        public String key() {
            return host + ":" + port;
        }
    }

    private final StateStore store;
    private final Path urlFile;
    private final Path listFile;
    /** the global proxy's relay, which the download goes through while it's on */
    private final Supplier<@Nullable InetSocketAddress> relay;
    private volatile List<Proxy> proxies = List.of();

    public ProxyPool(ManagerConfig config, StateStore store, Supplier<@Nullable InetSocketAddress> relay) {
        this(config.dataDir().resolve("manager").resolve("secrets"), store, relay);
    }

    public ProxyPool(Path secrets, StateStore store, Supplier<@Nullable InetSocketAddress> relay) {
        this.store = store;
        this.urlFile = secrets.resolve("proxy-pool-url");
        this.listFile = secrets.resolve("proxy-pool.txt");
        this.relay = relay;
        try {
            if (Files.exists(listFile)) proxies = parse(Files.readString(listFile));
        } catch (Exception e) {
            LOG.warn("Couldn't read the proxy pool", e);
        }
    }

    /** The account's proxy while the pool is on, and the first time a free one at random. */
    public synchronized ConfigComposer.@Nullable PoolProxy forAccount(String accountId) {
        var settings = store.read(s -> new Object[] { s.proxyPool.enabled, s.proxyPool.type, s.proxyPool.assignments.get(accountId) });
        if (!(boolean) settings[0]) return null;
        var type = (String) settings[1];
        var current = (String) settings[2];
        var list = proxies;
        if (current != null) {
            for (var p : list) if (p.key().equals(current)) return pool(type, p);
        }
        var taken = store.read(s -> {
            var accounts = new HashSet<String>();
            s.accounts.forEach(a -> accounts.add(a.id));
            var keys = new HashSet<String>();
            s.proxyPool.assignments.forEach((account, key) -> {
                if (!account.equals(accountId) && accounts.contains(account)) keys.add(key);
            });
            return keys;
        });
        var free = list.stream().filter(p -> !taken.contains(p.key())).toList();
        if (free.isEmpty()) return new ConfigComposer.PoolProxy(type, null, 0, "", "");
        var pick = free.get(ThreadLocalRandom.current().nextInt(free.size()));
        store.update(s -> s.proxyPool.assignments.put(accountId, pick.key()));
        if (current != null) LOG.info("Account {} moves from {}, which left the pool, to {}", accountId, current, pick.key());
        else LOG.info("Account {} gets the pool proxy {}", accountId, pick.key());
        return pool(type, pick);
    }

    private static ConfigComposer.PoolProxy pool(String type, Proxy p) {
        return new ConfigComposer.PoolProxy(type, p.host(), p.port(), p.user(), p.password());
    }

    public boolean hasUrl() {
        return Files.exists(urlFile);
    }

    /** Saves the list URL and downloads the list from it; nothing is saved if that fails. */
    public synchronized void setUrl(String url) throws IOException, InterruptedException {
        var uri = URI.create(url.trim());
        if (!"https".equals(uri.getScheme()) && !"http".equals(uri.getScheme())) throw new IllegalArgumentException("Use an http or https URL");
        var list = download(uri);
        Files.createDirectories(urlFile.getParent());
        SecureFiles.writeString(urlFile, uri.toString());
        saveList(list);
    }

    public synchronized void configure(@Nullable Boolean enabled, @Nullable String type) {
        if (type != null && !TYPES.contains(type)) throw new IllegalArgumentException("Unknown proxy type " + type);
        if (Boolean.TRUE.equals(enabled) && proxies.isEmpty()) throw new IllegalArgumentException("Add a proxy list first");
        store.update(s -> {
            if (enabled != null) s.proxyPool.enabled = enabled;
            if (type != null) s.proxyPool.type = type;
        });
    }

    /** Gives the account a different proxy from the pool the next time it connects, e.g. after a ban. */
    public synchronized void reassign(String accountId) {
        store.update(s -> s.proxyPool.assignments.remove(accountId));
    }

    /** Downloads the list again; an account whose proxy is gone gets a new one when it next connects. */
    public synchronized void refresh() throws IOException, InterruptedException {
        if (!hasUrl()) throw new IllegalStateException("No proxy list URL set");
        saveList(download(URI.create(Files.readString(urlFile).trim())));
    }

    public void startBackgroundRefresh() {
        Thread.ofVirtual().name("proxy-pool").start(() -> {
            while (true) {
                try {
                    long last = store.read(s -> s.proxyPool.refreshedAt);
                    long wait = last + REFRESH_EVERY.toMillis() - System.currentTimeMillis();
                    if (wait > 0) Thread.sleep(wait);
                    if (hasUrl()) {
                        try {
                            refresh();
                        } catch (IOException | RuntimeException e) {
                            LOG.warn("Couldn't refresh the proxy pool: {}", e.getMessage());
                            store.update(s -> {
                                s.proxyPool.error = e.getMessage();
                                // try again in an hour, not right away
                                s.proxyPool.refreshedAt = System.currentTimeMillis() - REFRESH_EVERY.toMillis() + 3_600_000;
                            });
                        }
                    } else {
                        Thread.sleep(REFRESH_EVERY.toMillis());
                    }
                } catch (InterruptedException e) {
                    return;
                }
            }
        });
    }

    public void forgetAccount(String accountId) {
        store.update(s -> s.proxyPool.assignments.remove(accountId));
    }

    private List<Proxy> download(URI uri) throws IOException, InterruptedException {
        var builder = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).followRedirects(HttpClient.Redirect.NORMAL);
        var via = relay.get();
        if (via != null) builder.proxy(ProxySelector.of(via));
        try (var http = builder.build()) {
            var response = http.send(HttpRequest.newBuilder(uri).timeout(Duration.ofSeconds(30)).GET().build(),
                HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() != 200) throw new IOException("The list URL answered with HTTP " + response.statusCode());
            var list = parse(response.body());
            if (list.isEmpty()) throw new IOException("The list URL returned no proxies");
            return list;
        }
    }

    private void saveList(List<Proxy> list) throws IOException {
        var text = new StringBuilder();
        for (var p : list) text.append(p.host()).append(':').append(p.port()).append(':').append(p.user()).append(':').append(p.password()).append('\n');
        Files.createDirectories(listFile.getParent());
        SecureFiles.writeString(listFile, text.toString());
        proxies = List.copyOf(list);
        LOG.info("Proxy pool has {} proxies", list.size());
        store.update(s -> {
            s.proxyPool.refreshedAt = System.currentTimeMillis();
            s.proxyPool.error = null;
            // deleted accounts free their proxy
            var accounts = new HashSet<String>();
            s.accounts.forEach(a -> accounts.add(a.id));
            s.proxyPool.assignments.keySet().removeIf(id -> !accounts.contains(id));
        });
    }

    /**
     * One proxy per line: {@code host:port}, {@code host:port:user:password} (Webshare's format) or
     * {@code scheme://user:password@host:port}. Blank lines and # comments are skipped, and so are duplicates.
     */
    static List<Proxy> parse(String text) {
        var byKey = new LinkedHashMap<String, Proxy>();
        for (var raw : text.split("\\R")) {
            var line = raw.strip();
            if (line.isEmpty() || line.startsWith("#")) continue;
            Proxy proxy;
            try {
                proxy = line.contains("@") || line.contains("://") ? parseUri(line) : parseColons(line);
            } catch (RuntimeException e) {
                proxy = null;
            }
            if (proxy == null || proxy.port() < 1 || proxy.port() > 65535 || proxy.host().isEmpty()) continue;
            byKey.putIfAbsent(proxy.key(), proxy);
        }
        return new ArrayList<>(byKey.values());
    }

    private static @Nullable Proxy parseColons(String line) {
        var parts = line.split(":", 4);
        return switch (parts.length) {
            case 2 -> new Proxy(parts[0], Integer.parseInt(parts[1]), "", "");
            case 4 -> new Proxy(parts[0], Integer.parseInt(parts[1]), parts[2], parts[3]);
            default -> null;
        };
    }

    private static Proxy parseUri(String line) {
        var uri = URI.create(line.contains("://") ? line : "socks5://" + line);
        var info = uri.getRawUserInfo();
        String user = "", password = "";
        if (info != null) {
            int colon = info.indexOf(':');
            user = URLDecoder.decode(colon < 0 ? info : info.substring(0, colon), StandardCharsets.UTF_8);
            password = colon < 0 ? "" : URLDecoder.decode(info.substring(colon + 1), StandardCharsets.UTF_8);
        }
        return new Proxy(uri.getHost(), uri.getPort(), user, password);
    }

    /** For the admin: never the URL or logins. */
    public JsonObject status() {
        var list = proxies;
        var keys = new HashSet<String>();
        list.forEach(p -> keys.add(p.key()));
        return store.read(s -> {
            var json = new JsonObject();
            json.addProperty("enabled", s.proxyPool.enabled);
            json.addProperty("type", s.proxyPool.type);
            json.addProperty("hasUrl", hasUrl());
            json.addProperty("size", list.size());
            if (s.proxyPool.refreshedAt > 0) json.addProperty("refreshedAt", s.proxyPool.refreshedAt);
            if (s.proxyPool.error != null) json.addProperty("error", s.proxyPool.error);
            var accounts = new JsonArray();
            int used = 0;
            for (State.Account account : s.accounts) {
                var a = new JsonObject();
                a.addProperty("accountId", account.id);
                a.addProperty("username", account.username);
                var key = s.proxyPool.assignments.get(account.id);
                if (key != null) {
                    a.addProperty("proxy", key);
                    // gone from the list: it gets a new one when it next connects
                    a.addProperty("inPool", keys.contains(key));
                    if (keys.contains(key)) used++;
                }
                accounts.add(a);
            }
            json.add("accounts", accounts);
            json.addProperty("free", Math.max(0, list.size() - used));
            return json;
        });
    }
}
