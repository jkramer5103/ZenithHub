package com.zenith.control;

import java.io.BufferedInputStream;
import java.io.ByteArrayOutputStream;
import java.io.Closeable;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.function.Supplier;
import java.util.regex.Pattern;

/**
 * A plain HTTP proxy on 127.0.0.1 that forwards every connection through an upstream SOCKS or HTTP proxy.
 * Java's HttpClient can't use SOCKS proxies, and Java's proxy logins apply to the whole process,
 * so HTTP clients point at this relay instead and the relay logs in to the upstream itself.
 * While {@code upstream} returns null the relay refuses connections rather than going direct.
 * The manager has a copy of this class (com.zenith.manager.util.ProxyRelay); change both together.
 */
public final class ProxyRelay implements Closeable {
    public enum Type { HTTP, SOCKS4, SOCKS5 }

    public record Upstream(Type type, String host, int port, String user, String password) {
        @Override
        public String toString() {
            return type + " " + host + ":" + port;
        }
    }

    private static final int TIMEOUT_MS = 15_000;
    private static final int MAX_HEADER_BYTES = 64 * 1024;
    private static final Pattern IPV4 = Pattern.compile("\\d{1,3}(\\.\\d{1,3}){3}");

    private final Supplier<Upstream> upstream;
    private final ServerSocket server;

    private ProxyRelay(Supplier<Upstream> upstream) throws IOException {
        this.upstream = upstream;
        this.server = new ServerSocket(0, 50, InetAddress.getLoopbackAddress());
        Thread.ofVirtual().name("proxy-relay").start(this::acceptLoop);
    }

    public static ProxyRelay start(Supplier<Upstream> upstream) throws IOException {
        return new ProxyRelay(upstream);
    }

    public InetSocketAddress address() {
        return new InetSocketAddress(server.getInetAddress(), server.getLocalPort());
    }

    @Override
    public void close() throws IOException {
        server.close();
    }

    /** A TCP connection to {@code host:port} through the upstream proxy. The proxy resolves the host name. */
    public static Socket connect(Upstream up, String host, int port) throws IOException {
        var socket = new Socket();
        try {
            socket.setTcpNoDelay(true);
            socket.connect(new InetSocketAddress(up.host(), up.port()), TIMEOUT_MS);
            socket.setSoTimeout(TIMEOUT_MS);
            var in = new BufferedInputStream(socket.getInputStream());
            var out = socket.getOutputStream();
            switch (up.type()) {
                case SOCKS5 -> socks5(in, out, up, host, port);
                case SOCKS4 -> socks4(in, out, up, host, port);
                case HTTP -> httpConnect(in, out, up, host, port);
            }
            socket.setSoTimeout(0);
            return socket;
        } catch (IOException | RuntimeException e) {
            socket.close();
            throw e;
        }
    }

    private void acceptLoop() {
        while (!server.isClosed()) {
            try {
                var client = server.accept();
                Thread.ofVirtual().name("proxy-relay-conn").start(() -> handle(client));
            } catch (IOException e) {
                if (server.isClosed()) return;
            }
        }
    }

    private void handle(Socket client) {
        Socket remote = null;
        try {
            client.setSoTimeout(TIMEOUT_MS);
            var in = new BufferedInputStream(client.getInputStream());
            var out = client.getOutputStream();
            var header = readHeader(in);
            var lines = header.split("\r\n");
            var request = lines[0].split(" ");
            if (request.length != 3) throw new IOException("Bad request line");
            var up = upstream.get();
            if (up == null) {
                respond(out, "503 Proxy Turned Off");
                return;
            }
            if (request[0].equalsIgnoreCase("CONNECT")) {
                var target = request[1];
                int colon = target.lastIndexOf(':');
                if (colon < 0) throw new IOException("CONNECT without a port");
                var host = target.substring(0, colon).replace("[", "").replace("]", "");
                int port = Integer.parseInt(target.substring(colon + 1));
                remote = connect(up, host, port);
                out.write("HTTP/1.1 200 Connection Established\r\n\r\n".getBytes(StandardCharsets.ISO_8859_1));
                out.flush();
            } else {
                // plain http://: forward the request in origin form, one request per connection
                var uri = URI.create(request[1]);
                if (uri.getHost() == null) throw new IOException("Not a proxy request");
                remote = connect(up, uri.getHost(), uri.getPort() > 0 ? uri.getPort() : 80);
                var path = uri.getRawPath() == null || uri.getRawPath().isEmpty() ? "/" : uri.getRawPath();
                if (uri.getRawQuery() != null) path += "?" + uri.getRawQuery();
                var rewritten = new StringBuilder(request[0]).append(' ').append(path).append(' ').append(request[2]).append("\r\n");
                for (int i = 1; i < lines.length; i++) {
                    var name = lines[i].contains(":") ? lines[i].substring(0, lines[i].indexOf(':')).trim() : "";
                    if (name.equalsIgnoreCase("Proxy-Connection") || name.equalsIgnoreCase("Proxy-Authorization")
                        || name.equalsIgnoreCase("Connection")) continue;
                    rewritten.append(lines[i]).append("\r\n");
                }
                rewritten.append("Connection: close\r\n\r\n");
                remote.getOutputStream().write(rewritten.toString().getBytes(StandardCharsets.ISO_8859_1));
            }
            client.setSoTimeout(0);
            pipe(in, out, remote);
        } catch (Exception e) {
            try {
                if (remote == null) respond(client.getOutputStream(), "502 Bad Gateway");
            } catch (IOException ignored) {}
        } finally {
            if (remote != null) try { remote.close(); } catch (IOException ignored) {}
            try { client.close(); } catch (IOException ignored) {}
        }
    }

    /** Copies both ways until either side closes. */
    private static void pipe(InputStream clientIn, OutputStream clientOut, Socket remote) throws IOException {
        var remoteIn = remote.getInputStream();
        var remoteOut = remote.getOutputStream();
        Thread.ofVirtual().name("proxy-relay-up").start(() -> {
            try {
                clientIn.transferTo(remoteOut);
                remote.shutdownOutput();
            } catch (IOException ignored) {
                try { remote.close(); } catch (IOException ignored2) {}
            }
        });
        try {
            remoteIn.transferTo(clientOut);
            clientOut.flush();
        } finally {
            // the client socket closes after this returns, which ends the upload too
            remote.close();
        }
    }

    private static void respond(OutputStream out, String status) throws IOException {
        out.write(("HTTP/1.1 " + status + "\r\nContent-Length: 0\r\nConnection: close\r\n\r\n").getBytes(StandardCharsets.ISO_8859_1));
        out.flush();
    }

    /** Reads up to and including the blank line after the headers; returns them without it. */
    private static String readHeader(InputStream in) throws IOException {
        var buf = new ByteArrayOutputStream();
        int last = 0;
        while (last != 0x0D0A0D0A) {
            int b = in.read();
            if (b < 0) throw new IOException("Connection closed during the header");
            buf.write(b);
            if (buf.size() > MAX_HEADER_BYTES) throw new IOException("Header too large");
            last = (last << 8) | b;
        }
        var text = buf.toString(StandardCharsets.ISO_8859_1);
        return text.substring(0, text.length() - 4);
    }

    // --- upstream handshakes ---

    private static void socks5(InputStream in, OutputStream out, Upstream up, String host, int port) throws IOException {
        boolean login = !up.user().isEmpty() || !up.password().isEmpty();
        out.write(login ? new byte[] { 5, 2, 0, 2 } : new byte[] { 5, 1, 0 });
        out.flush();
        if (readByte(in) != 5) throw new IOException("Not a SOCKS5 proxy");
        int method = readByte(in);
        if (method == 2 && login) {
            var user = up.user().getBytes(StandardCharsets.UTF_8);
            var pass = up.password().getBytes(StandardCharsets.UTF_8);
            if (user.length > 255 || pass.length > 255) throw new IOException("Proxy username or password too long");
            var auth = new ByteArrayOutputStream();
            auth.write(1);
            auth.write(user.length);
            auth.write(user);
            auth.write(pass.length);
            auth.write(pass);
            out.write(auth.toByteArray());
            out.flush();
            readByte(in);
            if (readByte(in) != 0) throw new IOException("The proxy rejected the username or password");
        } else if (method != 0) {
            throw new IOException("The proxy wants a login method we don't support");
        }
        var request = new ByteArrayOutputStream();
        request.write(new byte[] { 5, 1, 0 });
        if (IPV4.matcher(host).matches()) {
            request.write(1);
            request.write(InetAddress.getByName(host).getAddress());
        } else if (host.contains(":")) {
            request.write(4);
            request.write(InetAddress.getByName(host).getAddress());
        } else {
            var name = host.getBytes(StandardCharsets.US_ASCII);
            if (name.length > 255) throw new IOException("Host name too long");
            request.write(3);
            request.write(name.length);
            request.write(name);
        }
        request.write(port >> 8);
        request.write(port & 0xFF);
        out.write(request.toByteArray());
        out.flush();
        if (readByte(in) != 5) throw new IOException("Bad SOCKS5 reply");
        int reply = readByte(in);
        if (reply != 0) throw new IOException("The proxy couldn't connect (SOCKS5 error " + reply + ")");
        readByte(in);
        int skip = switch (readByte(in)) {
            case 1 -> 4;
            case 4 -> 16;
            case 3 -> readByte(in);
            default -> throw new IOException("Bad SOCKS5 address type");
        };
        in.readNBytes(skip + 2);
    }

    private static void socks4(InputStream in, OutputStream out, Upstream up, String host, int port) throws IOException {
        var request = new ByteArrayOutputStream();
        request.write(new byte[] { 4, 1, (byte) (port >> 8), (byte) port });
        boolean ip = IPV4.matcher(host).matches();
        // SOCKS4a: 0.0.0.1 asks the proxy to resolve the name that follows
        request.write(ip ? InetAddress.getByName(host).getAddress() : new byte[] { 0, 0, 0, 1 });
        request.write(up.user().getBytes(StandardCharsets.UTF_8));
        request.write(0);
        if (!ip) {
            request.write(host.getBytes(StandardCharsets.US_ASCII));
            request.write(0);
        }
        out.write(request.toByteArray());
        out.flush();
        var reply = in.readNBytes(8);
        if (reply.length != 8 || reply[1] != 0x5A) throw new IOException("The proxy couldn't connect (SOCKS4)");
    }

    private static void httpConnect(InputStream in, OutputStream out, Upstream up, String host, int port) throws IOException {
        var target = (host.contains(":") ? "[" + host + "]" : host) + ":" + port;
        var request = new StringBuilder("CONNECT ").append(target).append(" HTTP/1.1\r\nHost: ").append(target).append("\r\n");
        if (!up.user().isEmpty() || !up.password().isEmpty()) {
            var credentials = Base64.getEncoder().encodeToString((up.user() + ":" + up.password()).getBytes(StandardCharsets.UTF_8));
            request.append("Proxy-Authorization: Basic ").append(credentials).append("\r\n");
        }
        request.append("\r\n");
        out.write(request.toString().getBytes(StandardCharsets.ISO_8859_1));
        out.flush();
        var status = readHeader(in).split("\r\n")[0].split(" ");
        if (status.length < 2 || !status[1].equals("200")) {
            throw new IOException("The proxy couldn't connect (HTTP " + (status.length > 1 ? status[1] : "?") + ")");
        }
    }

    private static int readByte(InputStream in) throws IOException {
        int b = in.read();
        if (b < 0) throw new IOException("The proxy closed the connection");
        return b;
    }
}
