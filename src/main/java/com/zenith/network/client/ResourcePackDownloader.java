package com.zenith.network.client;

import com.google.common.hash.Hashing;
import com.zenith.control.OutboundProxy;
import org.geysermc.mcprotocollib.protocol.codec.MinecraftCodec;
import org.geysermc.mcprotocollib.protocol.data.game.ResourcePackStatus;
import org.geysermc.mcprotocollib.protocol.packet.common.clientbound.ClientboundResourcePackPushPacket;
import org.geysermc.mcprotocollib.protocol.packet.common.serverbound.ServerboundResourcePackPacket;
import org.jspecify.annotations.Nullable;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.HttpURLConnection;
import java.net.Inet6Address;
import java.net.InetAddress;
import java.net.URI;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.Locale;
import java.util.concurrent.ThreadLocalRandom;

import static com.zenith.Globals.CACHE;
import static com.zenith.Globals.CLIENT_LOG;
import static com.zenith.Globals.CONFIG;

/**
 * Downloads server resource packs the way a vanilla client does: same headers, hash check, a local cache by hash,
 * and the status replies spaced out like a real download and reload. Zenith never loads the packs.
 */
public final class ResourcePackDownloader {
    private static final long MAX_SIZE = 250L * 1024 * 1024;
    private static final int MAX_REDIRECTS = 20;
    private static final int KEEP_CACHED = 8;
    private static final Path CACHE_DIR = Path.of("resource-packs");
    // resource pack format of Zenith's native version, sent in X-Minecraft-Pack-Format
    private static final String PACK_FORMAT = "46";

    private ResourcePackDownloader() {}

    public static void handle(final ClientboundResourcePackPushPacket packet, final ClientSession session) {
        Thread.ofVirtual().name("resource-pack-download").start(() -> {
            try {
                download(packet, session);
            } catch (final InterruptedException e) {
                Thread.currentThread().interrupt();
            } catch (final Exception e) {
                CLIENT_LOG.warn("Resource pack download failed: {}", e.getMessage());
                reply(session, packet, ResourcePackStatus.FAILED_DOWNLOAD);
            }
        });
    }

    /** True if the URL points into a private network while the server we're on doesn't. */
    public static boolean isBlockedLocalUrl(final String url, final ClientSession session) {
        if (!CONFIG.client.privacy.blockLocalPackUrls) return false;
        try {
            var host = URI.create(url).getHost();
            return host != null && isLocal(host) && !isLocal(session.getHost());
        } catch (final Exception e) {
            return false;
        }
    }

    private static void download(final ClientboundResourcePackPushPacket packet, final ClientSession session) throws Exception {
        URL url;
        try {
            url = URI.create(packet.getUrl()).toURL();
            var protocol = url.getProtocol();
            if (!"http".equals(protocol) && !"https".equals(protocol)) throw new IllegalArgumentException();
        } catch (final Exception e) {
            reply(session, packet, ResourcePackStatus.INVALID_URL);
            return;
        }
        // the game thread handles the push on its next frame
        Thread.sleep(ThreadLocalRandom.current().nextLong(30, 120));
        reply(session, packet, ResourcePackStatus.ACCEPTED);
        if (isBlockedLocalUrl(packet.getUrl(), session)) {
            CLIENT_LOG.info("Blocked a resource pack from a private network: {}", packet.getUrl());
            Thread.sleep(ThreadLocalRandom.current().nextLong(5, 40)); // a refused connection fails fast
            reply(session, packet, ResourcePackStatus.FAILED_DOWNLOAD);
            return;
        }
        var hash = packet.getHash() == null ? "" : packet.getHash().toLowerCase(Locale.ROOT);
        var cached = hash.matches("[0-9a-f]{40}") ? CACHE_DIR.resolve(hash) : null;
        byte[] data;
        if (cached != null && Files.isRegularFile(cached)) {
            data = Files.readAllBytes(cached);
            Files.setLastModifiedTime(cached, java.nio.file.attribute.FileTime.fromMillis(System.currentTimeMillis()));
            CLIENT_LOG.info("Using cached resource pack {}", hash);
        } else {
            data = fetch(url, session);
            if (data == null) {
                reply(session, packet, ResourcePackStatus.FAILED_DOWNLOAD);
                return;
            }
            @SuppressWarnings("deprecation")
            var actual = Hashing.sha1().hashBytes(data).toString();
            if (!hash.isEmpty() && !hash.equals(actual)) {
                CLIENT_LOG.warn("Resource pack hash mismatch: expected {}, got {}", hash, actual);
                reply(session, packet, ResourcePackStatus.FAILED_DOWNLOAD);
                return;
            }
            if (cached != null) store(cached, data);
            CLIENT_LOG.info("Downloaded resource pack ({} KB)", data.length / 1024);
        }
        reply(session, packet, ResourcePackStatus.DOWNLOADED);
        if (data.length < 4 || data[0] != 'P' || data[1] != 'K') {
            reply(session, packet, ResourcePackStatus.FAILED_RELOAD);
            return;
        }
        // reloading resources takes a moment, longer for bigger packs
        Thread.sleep(400 + data.length / 20_000 + ThreadLocalRandom.current().nextLong(0, 600));
        reply(session, packet, ResourcePackStatus.SUCCESSFULLY_LOADED);
    }

    private static byte @Nullable [] fetch(URL url, final ClientSession session) throws IOException {
        for (int redirects = 0; redirects <= MAX_REDIRECTS; redirects++) {
            if (redirects > 0 && isBlockedLocalUrl(url.toString(), session)) {
                CLIENT_LOG.info("Blocked a resource pack redirect into a private network: {}", url);
                return null;
            }
            var connection = (HttpURLConnection) url.openConnection(OutboundProxy.javaProxy());
            connection.setInstanceFollowRedirects(false);
            connection.setConnectTimeout(15_000);
            connection.setReadTimeout(30_000);
            headers(connection);
            try {
                int code = connection.getResponseCode();
                if (code >= 300 && code < 400 && connection.getHeaderField("Location") != null) {
                    url = URI.create(url.toString()).resolve(connection.getHeaderField("Location")).toURL();
                    continue;
                }
                if (code != 200) {
                    CLIENT_LOG.warn("Resource pack download got HTTP {}", code);
                    return null;
                }
                if (connection.getContentLengthLong() > MAX_SIZE) return null;
                try (var in = connection.getInputStream()) {
                    var out = new ByteArrayOutputStream();
                    var buffer = new byte[16384];
                    int read;
                    while ((read = in.read(buffer)) != -1) {
                        out.write(buffer, 0, read);
                        if (out.size() > MAX_SIZE) return null;
                    }
                    return out.toByteArray();
                }
            } finally {
                connection.disconnect();
            }
        }
        return null;
    }

    private static void headers(final HttpURLConnection connection) {
        var version = MinecraftCodec.CODEC.getMinecraftVersion();
        var profile = CACHE.getProfileCache().getProfile();
        boolean scrub = CONFIG.client.privacy.scrubPackHeaders;
        connection.setRequestProperty("X-Minecraft-Username", scrub || profile == null ? "Player" : profile.getName());
        connection.setRequestProperty("X-Minecraft-UUID", scrub || profile == null
            ? "00000000000000000000000000000000"
            : profile.getId().toString().replace("-", ""));
        connection.setRequestProperty("X-Minecraft-Version", version);
        connection.setRequestProperty("X-Minecraft-Version-ID", version);
        connection.setRequestProperty("X-Minecraft-Pack-Format", PACK_FORMAT);
        connection.setRequestProperty("User-Agent", scrub ? "Minecraft" : "Minecraft Java/" + version);
    }

    private static void store(final Path file, final byte[] data) {
        try {
            Files.createDirectories(CACHE_DIR);
            Files.write(file, data);
            try (var files = Files.list(CACHE_DIR)) {
                files.sorted(Comparator.comparingLong(ResourcePackDownloader::modified).reversed())
                    .skip(KEEP_CACHED)
                    .forEach(old -> old.toFile().delete());
            }
        } catch (final IOException e) {
            CLIENT_LOG.debug("Unable to cache resource pack", e);
        }
    }

    private static long modified(final Path file) {
        try {
            return Files.getLastModifiedTime(file).toMillis();
        } catch (final IOException e) {
            return 0;
        }
    }

    private static void reply(final ClientSession session, final ClientboundResourcePackPushPacket packet, final ResourcePackStatus status) {
        if (session.isDisconnected()) return;
        session.sendAsync(new ServerboundResourcePackPacket(packet.getId(), status));
    }

    private static boolean isLocal(final String host) {
        if (host.equalsIgnoreCase("localhost") || host.endsWith(".local")) return true;
        try {
            for (var address : InetAddress.getAllByName(host)) {
                if (address.isLoopbackAddress() || address.isSiteLocalAddress() || address.isLinkLocalAddress() || address.isAnyLocalAddress()) return true;
                // unique local IPv6 (fc00::/7), which isSiteLocalAddress doesn't cover
                if (address instanceof Inet6Address && (address.getAddress()[0] & 0xfe) == 0xfc) return true;
            }
        } catch (final Exception e) {
            return false;
        }
        return false;
    }
}
