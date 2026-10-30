package com.zenith.manager;

import com.sun.net.httpserver.HttpServer;
import com.zenith.manager.util.ProxyRelay;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.DataInputStream;
import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ProxySelector;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ProxyRelayTest {
    private HttpServer target;
    private ServerSocket socks;
    /** host names the fake SOCKS5 proxy was asked to connect to */
    private final List<String> requested = new CopyOnWriteArrayList<>();

    @BeforeEach
    void start() throws IOException {
        target = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        target.createContext("/", exchange -> {
            var body = ("hello " + exchange.getRequestURI()).getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        target.start();
        socks = new ServerSocket(0, 50, InetAddress.getLoopbackAddress());
        Thread.ofVirtual().start(() -> {
            while (!socks.isClosed()) {
                try {
                    var client = socks.accept();
                    Thread.ofVirtual().start(() -> serveSocks5(client));
                } catch (IOException ignored) {}
            }
        });
    }

    @AfterEach
    void stop() throws IOException {
        target.stop(0);
        socks.close();
    }

    /** A SOCKS5 proxy that wants user "u" with password "p" and resolves names itself. */
    private void serveSocks5(Socket client) {
        try (client) {
            var in = new DataInputStream(client.getInputStream());
            var out = client.getOutputStream();
            if (in.readByte() != 5) return;
            var methods = in.readNBytes(in.readUnsignedByte());
            if (methods.length < 2 || methods[1] != 2) {
                out.write(new byte[] { 5, (byte) 0xFF });
                return;
            }
            out.write(new byte[] { 5, 2 });
            in.readByte();
            var user = new String(in.readNBytes(in.readUnsignedByte()), StandardCharsets.UTF_8);
            var pass = new String(in.readNBytes(in.readUnsignedByte()), StandardCharsets.UTF_8);
            boolean ok = user.equals("u") && pass.equals("p");
            out.write(new byte[] { 1, (byte) (ok ? 0 : 1) });
            if (!ok) return;
            in.readNBytes(3);
            if (in.readByte() != 3) return;
            var host = new String(in.readNBytes(in.readUnsignedByte()), StandardCharsets.US_ASCII);
            int port = in.readUnsignedShort();
            requested.add(host);
            try (var remote = new Socket(host, port)) {
                out.write(new byte[] { 5, 0, 0, 1, 0, 0, 0, 0, 0, 0 });
                Thread.ofVirtual().start(() -> {
                    try {
                        in.transferTo(remote.getOutputStream());
                    } catch (IOException ignored) {}
                });
                remote.getInputStream().transferTo(out);
            }
        } catch (IOException ignored) {}
    }

    private ProxyRelay.Upstream upstream() {
        return new ProxyRelay.Upstream(ProxyRelay.Type.SOCKS5, "127.0.0.1", socks.getLocalPort(), "u", "p");
    }

    @Test
    void plainHttpGoesThroughTheUpstream() throws Exception {
        try (var relay = ProxyRelay.start(this::upstream);
             var http = HttpClient.newBuilder().proxy(ProxySelector.of(relay.address())).build()) {
            var uri = URI.create("http://localhost:" + target.getAddress().getPort() + "/a?b=c");
            var response = http.send(HttpRequest.newBuilder(uri).build(), HttpResponse.BodyHandlers.ofString());
            assertEquals(200, response.statusCode());
            assertEquals("hello /a?b=c", response.body());
            // the proxy resolved the name, not us
            assertEquals(List.of("localhost"), requested);
        }
    }

    @Test
    void connectTunnelsThroughTheUpstream() throws Exception {
        try (var relay = ProxyRelay.start(this::upstream); var socket = new Socket()) {
            socket.connect(relay.address());
            var port = target.getAddress().getPort();
            socket.getOutputStream().write(("CONNECT localhost:" + port + " HTTP/1.1\r\nHost: localhost:" + port + "\r\n\r\n"
                + "GET /tunnel HTTP/1.1\r\nHost: localhost\r\nConnection: close\r\n\r\n").getBytes(StandardCharsets.ISO_8859_1));
            var reply = new String(socket.getInputStream().readAllBytes(), StandardCharsets.ISO_8859_1);
            assertTrue(reply.startsWith("HTTP/1.1 200 Connection Established\r\n\r\nHTTP/1.1 200"), reply);
            assertTrue(reply.endsWith("hello /tunnel"), reply);
        }
    }

    @Test
    void refusesInsteadOfGoingDirectWhileOff() throws Exception {
        try (var relay = ProxyRelay.start(() -> null);
             var http = HttpClient.newBuilder().proxy(ProxySelector.of(relay.address())).build()) {
            var uri = URI.create("http://localhost:" + target.getAddress().getPort() + "/");
            assertEquals(503, http.send(HttpRequest.newBuilder(uri).build(), HttpResponse.BodyHandlers.ofString()).statusCode());
            assertTrue(requested.isEmpty());
        }
    }

    @Test
    void wrongLoginFails() throws Exception {
        try (var relay = ProxyRelay.start(() -> new ProxyRelay.Upstream(ProxyRelay.Type.SOCKS5, "127.0.0.1", socks.getLocalPort(), "u", "wrong"));
             var http = HttpClient.newBuilder().proxy(ProxySelector.of(relay.address())).build()) {
            var uri = URI.create("http://localhost:" + target.getAddress().getPort() + "/");
            assertEquals(502, http.send(HttpRequest.newBuilder(uri).build(), HttpResponse.BodyHandlers.ofString()).statusCode());
        }
    }
}
