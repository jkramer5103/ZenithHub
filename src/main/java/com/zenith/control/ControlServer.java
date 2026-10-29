package com.zenith.control;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import com.zenith.Proxy;
import com.zenith.command.api.CommandContext;
import com.zenith.discord.Embed;
import com.zenith.event.client.ClientConnectEvent;
import com.zenith.event.client.ClientDisconnectEvent;
import com.zenith.event.console.ConsoleLogEvent;
import com.zenith.event.discord.MainChannelMessageSentEvent;
import com.zenith.feature.queue.Queue;

import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Set;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;

import static com.github.rfresh2.EventConsumer.of;
import static com.zenith.Globals.*;
import static java.util.Objects.nonNull;

/**
 * Localhost HTTP API used by the manager to observe and control this instance.
 *
 * GET  /v1/status   - instance status snapshot
 * POST /v1/command  - {"command": "..."} executes a Zenith command, returns its output
 * POST /v1/shutdown - graceful shutdown
 * GET  /v1/events   - server-sent events stream of structured log lines, Discord notifications and chat relay embeds
 * GET  /v1/config   - the live config
 * POST /v1/config   - {"settings": {...nested partial config}} changes settings live and saves them
 */
public final class ControlServer {
    public static final ControlServer INSTANCE = new ControlServer();
    private static final int EVENT_QUEUE_SIZE = 2048;
    // SSE data must be a single line
    // "[date] [logger] [level] " from the terminal pattern in logback.xml
    private static final Pattern LOG_PREFIX = Pattern.compile("^\\[[^\\]]*] \\[[^\\]]*] \\[[A-Z]+] ");
    private static final Gson COMPACT_GSON = new GsonBuilder().disableHtmlEscaping().create();

    private final Set<BlockingQueue<String>> eventSubscribers = ConcurrentHashMap.newKeySet();
    private HttpServer server;
    // why the last connection ended, so the UI can say why "connect" didn't stick
    private volatile String lastDisconnectReason;
    private volatile long lastDisconnectAt;
    private byte[] token;

    private ControlServer() {}

    public synchronized void start(int port, String token) throws IOException {
        if (server != null) return;
        this.token = token.getBytes(StandardCharsets.UTF_8);
        server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), port), 16);
        server.setExecutor(Executors.newVirtualThreadPerTaskExecutor());
        server.createContext("/v1/status", ex -> handle(ex, "GET", this::status));
        server.createContext("/v1/command", ex -> handle(ex, "POST", this::command));
        server.createContext("/v1/shutdown", ex -> handle(ex, "POST", this::shutdown));
        server.createContext("/v1/events", this::events);
        server.createContext("/v1/config", ex -> handle(ex, ex.getRequestMethod().equals("POST") ? "POST" : "GET",
            ex.getRequestMethod().equals("POST") ? this::updateConfig : this::config));
        EVENT_BUS.subscribe(
            this,
            of(ConsoleLogEvent.class, this::onConsoleLog),
            of(MainChannelMessageSentEvent.class, e -> onDiscordMessage("notification", e.embed(), e.message())),
            of(ClientConnectEvent.class, e -> lastDisconnectReason = null),
            of(ClientDisconnectEvent.class, e -> {
                lastDisconnectReason = e.manualDisconnect() ? null : friendlyReason(e.reason());
                lastDisconnectAt = System.currentTimeMillis();
            }));
        server.start();
        DEFAULT_LOG.info("Control API listening on 127.0.0.1:{}", port);
    }

    private interface JsonHandler {
        JsonObject handle(HttpExchange ex) throws Exception;
    }

    private void handle(HttpExchange ex, String method, JsonHandler handler) throws IOException {
        try (ex) {
            if (!authorized(ex)) {
                send(ex, 401, error("unauthorized"));
                return;
            }
            if (!ex.getRequestMethod().equals(method)) {
                send(ex, 405, error("method not allowed"));
                return;
            }
            send(ex, 200, handler.handle(ex));
        } catch (final IllegalArgumentException e) {
            try {
                send(ex, 400, error(e.getMessage()));
            } catch (IOException ignored) { }
        } catch (final Exception e) {
            DEFAULT_LOG.debug("Control API error", e);
            try {
                send(ex, 500, error(e.getClass().getSimpleName() + ": " + e.getMessage()));
            } catch (IOException ignored) { }
        }
    }

    private boolean authorized(HttpExchange ex) {
        var header = ex.getRequestHeaders().getFirst("Authorization");
        if (header == null || !header.startsWith("Bearer ")) return false;
        return MessageDigest.isEqual(token, header.substring(7).getBytes(StandardCharsets.UTF_8));
    }

    /** Network errors arrive as exception text; say what they mean instead. */
    static String friendlyReason(String reason) {
        if (reason == null) return null;
        if (reason.contains("Connection refused")) return "The server refused the connection. Check that it's online and the address is right.";
        if (reason.contains("UnknownHost") || reason.contains("UnresolvedAddress") || reason.contains("Name or service not known"))
            return "The server address couldn't be found. Check the address.";
        if (reason.contains("timed out") || reason.contains("ConnectTimeout")) return "The server didn't respond in time.";
        if (reason.contains("No route to host") || reason.contains("Network is unreachable")) return "The server can't be reached from here.";
        return reason;
    }

    private JsonObject status(HttpExchange ex) {
        var proxy = Proxy.getInstance();
        var json = new JsonObject();
        boolean connected = proxy.isConnected();
        json.addProperty("connected", connected);
        json.addProperty("inQueue", proxy.isInQueue());
        json.addProperty("online", connected && !proxy.isInQueue());
        json.addProperty("queuePosition", proxy.getQueuePosition());
        if (connected && proxy.isInQueue() && proxy.getQueuePosition() > 0) {
            json.addProperty("queueEtaSeconds", Queue.getQueueWait(proxy.getQueuePosition()));
        }
        json.addProperty("prio", proxy.isPrio());
        json.addProperty("username", CONFIG.authentication.username);
        json.addProperty("server", CONFIG.client.server.address + ":" + CONFIG.client.server.port);
        json.addProperty("uptimeSeconds", (System.currentTimeMillis() - proxy.getStartTime()) / 1000L);
        json.addProperty("onlineSeconds", connected ? proxy.getOnlineTimeSeconds() : 0);
        json.addProperty("ping", connected && proxy.getClient() != null ? proxy.getClient().getPing() : 0);
        json.addProperty("tps", TPS.getTPSValue());
        var reason = lastDisconnectReason;
        if (!connected && reason != null) {
            json.addProperty("lastDisconnectReason", reason);
            json.addProperty("lastDisconnectAt", lastDisconnectAt);
        }
        if (connected) {
            var player = CACHE.getPlayerCache().getThePlayer();
            json.addProperty("health", player.getHealth());
            json.addProperty("food", player.getFood());
            var pos = new JsonObject();
            pos.addProperty("x", player.getX());
            pos.addProperty("y", player.getY());
            pos.addProperty("z", player.getZ());
            var dimension = CACHE.getChunkCache().getCurrentDimension();
            pos.addProperty("dimension", nonNull(dimension) ? dimension.name() : null);
            json.add("position", pos);
        }
        var controller = proxy.getCurrentPlayer().get();
        json.addProperty("controller", nonNull(controller) ? controller.getName() : null);
        var spectators = new JsonArray();
        proxy.getSpectatorConnections().forEach(s -> spectators.add(s.getName()));
        json.add("spectators", spectators);
        return json;
    }

    private JsonObject command(HttpExchange ex) throws IOException {
        JsonObject body;
        try (var reader = new InputStreamReader(ex.getRequestBody(), StandardCharsets.UTF_8)) {
            body = GSON.fromJson(reader, JsonObject.class);
        }
        if (body == null || !body.has("command")) throw new IllegalArgumentException("missing command");
        var input = body.get("command").getAsString();
        var actor = body.has("actor") ? body.get("actor").getAsString() : "manager";
        var ctx = CommandContext.create(input, new ManagerCommandSource(actor));
        COMMAND.execute(ctx);
        if (!ctx.isSensitiveInput()) TERMINAL_LOG.info("{} executed: {}", actor, input);
        var json = new JsonObject();
        json.add("embed", embedToJson(ctx.getEmbed()));
        var lines = new JsonArray();
        ctx.getMultiLineOutput().forEach(lines::add);
        json.add("lines", lines);
        return json;
    }

    private JsonObject config(HttpExchange ex) {
        return GSON.toJsonTree(CONFIG).getAsJsonObject();
    }

    private JsonObject updateConfig(HttpExchange ex) throws IOException {
        JsonObject body;
        try (var reader = new InputStreamReader(ex.getRequestBody(), StandardCharsets.UTF_8)) {
            body = GSON.fromJson(reader, JsonObject.class);
        }
        if (body == null || !body.has("settings") || !body.get("settings").isJsonObject()) {
            throw new IllegalArgumentException("missing settings");
        }
        synchronized (CONFIG) {
            ConfigReflection.apply(CONFIG, body.getAsJsonObject("settings"));
        }
        // modules pick up their enabled flag; everything else reads CONFIG directly
        MODULE.getModules().forEach(module -> {
            try {
                module.syncEnabledFromConfig();
            } catch (final Exception e) {
                DEFAULT_LOG.warn("Failed applying settings to {}", module.getClass().getSimpleName(), e);
            }
        });
        saveConfigAsync();
        return config(ex);
    }

    /** The manager reads stdout only while nobody is subscribed, so lines aren't seen twice. */
    public boolean hasSubscribers() {
        return !eventSubscribers.isEmpty();
    }

    private JsonObject shutdown(HttpExchange ex) {
        EXECUTOR.schedule(() -> Proxy.getInstance().stop(false), 100, TimeUnit.MILLISECONDS);
        var json = new JsonObject();
        json.addProperty("ok", true);
        return json;
    }

    private void events(HttpExchange ex) throws IOException {
        try (ex) {
            if (!authorized(ex)) {
                send(ex, 401, error("unauthorized"));
                return;
            }
            var queue = new ArrayBlockingQueue<String>(EVENT_QUEUE_SIZE);
            eventSubscribers.add(queue);
            try {
                ex.getResponseHeaders().set("Content-Type", "text/event-stream");
                ex.getResponseHeaders().set("Cache-Control", "no-cache");
                ex.sendResponseHeaders(200, 0);
                OutputStream out = ex.getResponseBody();
                while (true) {
                    var msg = queue.poll(15, TimeUnit.SECONDS);
                    // keepalive comment lets us notice dead connections
                    out.write((msg == null ? ":\n\n" : "data: " + msg + "\n\n").getBytes(StandardCharsets.UTF_8));
                    out.flush();
                }
            } catch (InterruptedException | IOException e) {
                // client went away
            } finally {
                eventSubscribers.remove(queue);
            }
        }
    }

    // runs on logback's appender thread: never block here
    private void onConsoleLog(ConsoleLogEvent event) {
        if (eventSubscribers.isEmpty()) return;
        var log = event.logbackEvent();
        var json = new JsonObject();
        json.addProperty("type", "log");
        json.addProperty("ts", log.getTimeStamp());
        json.addProperty("logger", log.getLoggerName());
        json.addProperty("level", log.getLevel().toString());
        // the manager shows time, logger and level itself
        json.addProperty("text", stripPrefix(event.plain()));
        json.addProperty("ansi", stripPrefix(event.ansi()));
        var msg = COMPACT_GSON.toJson(json);
        for (var queue : eventSubscribers) {
            queue.offer(msg);
        }
    }

    /** What Zenith's own Discord bot would post; the manager's bot posts it instead. */
    private void onDiscordMessage(String type, Embed embed, String message) {
        if (eventSubscribers.isEmpty()) return;
        var json = new JsonObject();
        json.addProperty("type", type);
        json.addProperty("ts", System.currentTimeMillis());
        if (embed != null) json.add("embed", embedToJson(embed));
        if (message != null) {
            // role mentions stand for "ping the account owner"; the manager knows who that is
            json.addProperty("mention", message.contains("<@"));
            var text = message.replaceAll("<@[&!]?\\d+>", "").strip();
            if (!text.isEmpty()) json.addProperty("text", text);
        }
        var msg = COMPACT_GSON.toJson(json);
        for (var queue : eventSubscribers) {
            queue.offer(msg);
        }
    }

    private static String stripPrefix(String line) {
        return LOG_PREFIX.matcher(line).replaceFirst("").stripTrailing();
    }

    private static JsonObject embedToJson(Embed embed) {
        var json = new JsonObject();
        json.addProperty("title", embed.title());
        json.addProperty("description", embed.description());
        json.addProperty("url", embed.url());
        if (embed.color() != null) json.addProperty("color", embed.color().getRGB() & 0xFFFFFF);
        if (embed.timestamp() != null) json.addProperty("timestamp", embed.timestamp().toEpochMilli());
        json.addProperty("image", embed.image());
        json.addProperty("thumbnail", embed.thumbnail());
        if (embed.footer() != null) {
            var footer = new JsonObject();
            footer.addProperty("text", embed.footer().text());
            footer.addProperty("iconUrl", embed.footer().iconUrl());
            json.add("footer", footer);
        }
        if (embed.author() != null) {
            var author = new JsonObject();
            author.addProperty("name", embed.author().name());
            author.addProperty("url", embed.author().url());
            author.addProperty("iconUrl", embed.author().iconUrl());
            json.add("author", author);
        }
        var fields = new JsonArray();
        for (var field : embed.fields()) {
            var f = new JsonObject();
            f.addProperty("name", field.name());
            f.addProperty("value", field.value());
            f.addProperty("inline", field.inline());
            fields.add(f);
        }
        json.add("fields", fields);
        return json;
    }

    private static JsonObject error(String message) {
        var json = new JsonObject();
        json.addProperty("error", message);
        return json;
    }

    private static void send(HttpExchange ex, int code, JsonObject body) throws IOException {
        var bytes = GSON.toJson(body).getBytes(StandardCharsets.UTF_8);
        ex.getResponseHeaders().set("Content-Type", "application/json");
        ex.sendResponseHeaders(code, bytes.length);
        ex.getResponseBody().write(bytes);
    }
}
