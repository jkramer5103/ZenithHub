package com.zenith.manager.lobby;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;

/**
 * The public Minecraft port. Fresh connections and pings go to the lobby; clients the lobby transferred
 * carry a {@code zenith:target} cookie naming an instance, and are piped to that instance's local port.
 *
 * Only reads the unencrypted handshake and login start, so it doesn't need to understand the rest of the protocol.
 */
public final class Router {
    private static final Logger LOG = LoggerFactory.getLogger("Router");
    /** Minecraft 1.21.4, the version the lobby speaks; ViaVersion translates other clients. */
    public static final int PROTOCOL = 769;
    public static final String TARGET_COOKIE = "zenith:target";
    private static final int MAX_FRAME = 8192;
    private static final int MAX_CONNECTIONS_PER_IP = 8;

    private final int port;
    private final Function<String, Integer> instancePort;
    private final int lobbyPort;
    private final Map<String, AtomicInteger> perIp = new ConcurrentHashMap<>();
    private volatile ServerSocket socket;

    /**
     * @param instancePort local Minecraft port of a running instance, or null if it isn't running
     */
    public Router(int port, int lobbyPort, Function<String, Integer> instancePort) {
        this.port = port;
        this.lobbyPort = lobbyPort;
        this.instancePort = instancePort;
    }

    public void start() throws IOException {
        var server = new ServerSocket();
        server.setReuseAddress(true);
        server.bind(new InetSocketAddress(port));
        socket = server;
        Thread.ofPlatform().daemon().name("router").start(() -> {
            while (!server.isClosed()) {
                try {
                    var client = server.accept();
                    Thread.ofVirtual().name("route").start(() -> handle(client));
                } catch (IOException e) {
                    if (!server.isClosed()) LOG.warn("Accept failed: {}", e.getMessage());
                }
            }
        });
        LOG.info("Minecraft lobby listening on port {}", port);
    }

    public void stop() {
        try {
            if (socket != null) socket.close();
        } catch (IOException ignored) {
        }
    }

    private void handle(Socket client) {
        var ip = client.getInetAddress().getHostAddress();
        var count = perIp.computeIfAbsent(ip, k -> new AtomicInteger());
        try (client) {
            if (count.incrementAndGet() > MAX_CONNECTIONS_PER_IP) return;
            client.setTcpNoDelay(true);
            client.setSoTimeout(10_000);
            var in = new DataInputStream(client.getInputStream());
            var out = client.getOutputStream();

            var handshakeFrame = readFrame(in);
            if (handshakeFrame == null) return;
            var handshake = Handshake.parse(handshakeFrame);
            if (handshake == null) return;

            switch (handshake.intent) {
                // unsupported clients see the lobby's own version, so their server list shows it as incompatible
                case 1 -> pipe(client, in, out, lobbyPort,
                    (LobbyVia.supports(handshake.protocol) ? handshake : handshake.withProtocol(PROTOCOL)).frame());
                case 2, 3 -> {
                    if (!LobbyVia.supports(handshake.protocol)) {
                        loginDisconnect(out, handshake.protocol < LobbyVia.MIN_PROTOCOL
                            ? "This server needs Minecraft 1.20.5 or newer."
                            : "This Minecraft version is too new for this server. Update ZenithProxy.");
                        return;
                    }
                    if (handshake.intent == 2) {
                        pipe(client, in, out, lobbyPort, handshake.frame());
                        return;
                    }
                    // a transfer: ask the client where the lobby sent it
                    var loginStart = readFrame(in);
                    if (loginStart == null) return;
                    writeFrame(out, cookieRequest());
                    var target = readCookieResponse(in);
                    Integer instance = target == null || target.equals("lobby") ? null : instancePort.apply(target);
                    if (instance != null) {
                        pipe(client, in, out, instance, handshake.frame(), loginStart);
                    } else {
                        // back to the lobby as a regular login
                        pipe(client, in, out, lobbyPort, handshake.withIntent(2).frame(), loginStart);
                    }
                }
                default -> {}
            }
        } catch (SocketTimeoutException | EOFException ignored) {
        } catch (IOException e) {
            LOG.debug("Connection from {} failed: {}", ip, e.getMessage());
        } finally {
            if (count.decrementAndGet() <= 0) perIp.remove(ip, count);
        }
    }

    /** Sends the already-read frames to the target, then copies bytes both ways until either side closes. */
    private static void pipe(Socket client, InputStream in, OutputStream out, int targetPort, byte[]... replay) throws IOException {
        try (var upstream = new Socket()) {
            upstream.setTcpNoDelay(true);
            upstream.connect(new InetSocketAddress("127.0.0.1", targetPort), 5000);
            client.setSoTimeout(0);
            var upOut = upstream.getOutputStream();
            for (var frame : replay) writeFrame(upOut, frame);
            upOut.flush();
            var back = Thread.ofVirtual().start(() -> {
                try {
                    upstream.getInputStream().transferTo(out);
                } catch (IOException ignored) {
                } finally {
                    closeQuietly(client);
                }
            });
            try {
                in.transferTo(upOut);
            } catch (IOException ignored) {
            } finally {
                closeQuietly(upstream);
            }
            try {
                back.join();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        } catch (IOException e) {
            LOG.debug("Couldn't reach local port {}: {}", targetPort, e.getMessage());
        }
    }

    private static void closeQuietly(Socket s) {
        try {
            s.close();
        } catch (IOException ignored) {
        }
    }

    // --- wire format ---

    record Handshake(int protocol, String host, int port, int intent) {
        static Handshake parse(byte[] frame) {
            try {
                var in = new DataInputStream(new java.io.ByteArrayInputStream(frame));
                if (readVarInt(in) != 0x00) return null;
                int protocol = readVarInt(in);
                var host = readString(in, 255);
                int port = in.readUnsignedShort();
                int intent = readVarInt(in);
                return new Handshake(protocol, host, port, intent);
            } catch (IOException e) {
                return null;
            }
        }

        Handshake withIntent(int newIntent) {
            return new Handshake(protocol, host, port, newIntent);
        }

        Handshake withProtocol(int newProtocol) {
            return new Handshake(newProtocol, host, port, intent);
        }

        byte[] frame() {
            var buf = new ByteArrayOutputStream();
            writeVarInt(buf, 0x00);
            writeVarInt(buf, protocol);
            writeString(buf, host);
            buf.write(port >> 8);
            buf.write(port & 0xFF);
            writeVarInt(buf, intent);
            return buf.toByteArray();
        }
    }

    private static byte[] cookieRequest() {
        var buf = new ByteArrayOutputStream();
        writeVarInt(buf, 0x05);
        writeString(buf, TARGET_COOKIE);
        return buf.toByteArray();
    }

    /** The cookie's value, or null when the client has none. */
    private static String readCookieResponse(DataInputStream in) throws IOException {
        var frame = readFrame(in);
        if (frame == null) return null;
        var body = new DataInputStream(new java.io.ByteArrayInputStream(frame));
        if (readVarInt(body) != 0x04) return null;
        if (!TARGET_COOKIE.equals(readString(body, 255))) return null;
        if (!body.readBoolean()) return null;
        int length = readVarInt(body);
        if (length < 0 || length > 64) return null;
        return new String(body.readNBytes(length), StandardCharsets.UTF_8);
    }

    private static void loginDisconnect(OutputStream out, String message) throws IOException {
        var buf = new ByteArrayOutputStream();
        writeVarInt(buf, 0x00);
        var json = new com.google.gson.JsonObject();
        json.addProperty("text", message);
        writeString(buf, json.toString());
        writeFrame(out, buf.toByteArray());
        out.flush();
    }

    /** One length-prefixed packet, or null for a legacy (pre-1.7) ping or an oversized frame. */
    private static byte[] readFrame(DataInputStream in) throws IOException {
        int length = readVarInt(in);
        if (length <= 0 || length > MAX_FRAME) return null;
        return in.readNBytes(length);
    }

    private static void writeFrame(OutputStream out, byte[] frame) throws IOException {
        var buf = new ByteArrayOutputStream();
        writeVarInt(buf, frame.length);
        buf.write(frame);
        out.write(buf.toByteArray());
    }

    private static int readVarInt(DataInputStream in) throws IOException {
        int value = 0;
        for (int i = 0; i < 5; i++) {
            int b = in.readUnsignedByte();
            value |= (b & 0x7F) << (7 * i);
            if ((b & 0x80) == 0) return value;
        }
        throw new IOException("VarInt too long");
    }

    private static void writeVarInt(ByteArrayOutputStream out, int value) {
        while ((value & ~0x7F) != 0) {
            out.write((value & 0x7F) | 0x80);
            value >>>= 7;
        }
        out.write(value);
    }

    private static String readString(DataInputStream in, int maxChars) throws IOException {
        int length = readVarInt(in);
        if (length < 0 || length > maxChars * 3) throw new IOException("String too long");
        return new String(in.readNBytes(length), StandardCharsets.UTF_8);
    }

    private static void writeString(ByteArrayOutputStream out, String s) {
        var bytes = s.getBytes(StandardCharsets.UTF_8);
        writeVarInt(out, bytes.length);
        out.writeBytes(bytes);
    }
}
