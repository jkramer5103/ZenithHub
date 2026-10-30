package com.zenith.control;

import net.lenni0451.commons.httpclient.proxy.ProxyHandler;
import net.lenni0451.commons.httpclient.proxy.ProxyType;
import org.geysermc.mcprotocollib.network.ProxyInfo;
import org.jspecify.annotations.Nullable;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.InetSocketAddress;
import java.net.Proxy;
import java.net.ProxySelector;
import java.net.Socket;
import java.net.http.HttpClient;

import static com.zenith.Globals.CONFIG;

/**
 * While {@code client.connectionProxy} is on, all of the bot's own traffic goes through it, not only the server
 * connection: sign-in, the session server, skins, server pings, resource packs and every web API.
 * HTTP clients use a {@link ProxyRelay} on 127.0.0.1 that follows the live config.
 */
public final class OutboundProxy {
    private static @Nullable ProxyRelay relay;

    private OutboundProxy() {}

    public static ProxyRelay.@Nullable Upstream upstream() {
        var proxy = CONFIG.client.connectionProxy;
        if (!proxy.enabled) return null;
        var type = switch (proxy.type) {
            case HTTP -> ProxyRelay.Type.HTTP;
            case SOCKS4 -> ProxyRelay.Type.SOCKS4;
            case SOCKS5 -> ProxyRelay.Type.SOCKS5;
        };
        return new ProxyRelay.Upstream(type, proxy.host, proxy.port, proxy.user, proxy.password);
    }

    /** The relay's address while the proxy is on, otherwise null (connect directly). */
    public static @Nullable InetSocketAddress relayAddress() {
        if (upstream() == null) return null;
        synchronized (OutboundProxy.class) {
            if (relay == null) {
                try {
                    relay = ProxyRelay.start(OutboundProxy::upstream);
                } catch (IOException e) {
                    // never fall back to a direct connection
                    throw new UncheckedIOException("Couldn't start the proxy relay", e);
                }
            }
            return relay.address();
        }
    }

    public static HttpClient.Builder apply(HttpClient.Builder builder) {
        var address = relayAddress();
        return address != null ? builder.proxy(ProxySelector.of(address)) : builder;
    }

    public static net.lenni0451.commons.httpclient.HttpClient apply(net.lenni0451.commons.httpclient.HttpClient client) {
        var address = relayAddress();
        if (address != null) client.setProxyHandler(new ProxyHandler(ProxyType.HTTP, address.getHostString(), address.getPort()));
        return client;
    }

    /** For URLConnection. */
    public static Proxy javaProxy() {
        var address = relayAddress();
        return address != null ? new Proxy(Proxy.Type.HTTP, address) : Proxy.NO_PROXY;
    }

    /** For MCProtocolLib's session service (joining online-mode servers). */
    public static @Nullable ProxyInfo sessionServiceProxy() {
        var address = relayAddress();
        return address != null ? new ProxyInfo(ProxyInfo.Type.HTTP, address) : null;
    }

    /** A raw TCP connection, through the proxy while it's on. */
    public static Socket connect(InetSocketAddress target, int timeoutMs) throws IOException {
        var up = upstream();
        if (up != null) return ProxyRelay.connect(up, target.getHostString(), target.getPort());
        var socket = new Socket();
        try {
            socket.connect(target, timeoutMs);
        } catch (IOException e) {
            socket.close();
            throw e;
        }
        return socket;
    }
}
