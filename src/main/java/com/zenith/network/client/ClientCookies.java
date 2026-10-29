package com.zenith.network.client;

import net.kyori.adventure.key.Key;
import org.geysermc.mcprotocollib.protocol.data.handshake.HandshakeIntent;
import org.jspecify.annotations.Nullable;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import static com.zenith.Globals.CONFIG;

/**
 * Cookies the server stored on us. Like vanilla they live for one connection and survive transfers to another
 * server. With {@code client.privacy.blockCookies} nothing is stored, so every request is answered with "not set".
 */
public final class ClientCookies {
    private static final Map<Key, byte[]> COOKIES = new ConcurrentHashMap<>();
    private static volatile @Nullable String transferTarget = null;

    private ClientCookies() {}

    public static void store(final Key key, final byte[] payload) {
        if (CONFIG.client.privacy.blockCookies) return;
        COOKIES.put(key, payload);
    }

    public static byte @Nullable [] get(final Key key) {
        if (CONFIG.client.privacy.blockCookies) return null;
        return COOKIES.get(key);
    }

    public static int count() {
        return COOKIES.size();
    }

    /** The next connection to this server follows a transfer: it keeps the cookies and says so in its handshake. */
    public static void markTransfer(final String host, final int port) {
        transferTarget = host + ":" + port;
    }

    /** Called when a connection starts. Returns the handshake intent to use. */
    public static HandshakeIntent startConnection(final String host, final int port) {
        var target = transferTarget;
        transferTarget = null;
        if ((host + ":" + port).equals(target)) return HandshakeIntent.TRANSFER;
        COOKIES.clear();
        return HandshakeIntent.LOGIN;
    }
}
