package com.zenith.control;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import com.viaversion.viaversion.api.protocol.version.ProtocolVersion;
import com.zenith.Proxy;
import com.zenith.command.api.CommandContext;
import com.zenith.discord.Embed;
import com.zenith.event.client.ClientConnectEvent;
import com.zenith.event.client.ClientDisconnectEvent;
import com.zenith.event.console.ConsoleLogEvent;
import com.zenith.event.discord.MainChannelMessageSentEvent;
import com.zenith.event.player.PlayerConnectedEvent;
import com.zenith.event.player.PlayerDisconnectedEvent;
import com.zenith.event.player.SpectatorConnectedEvent;
import com.zenith.event.player.SpectatorDisconnectedEvent;
import com.zenith.cache.data.entity.Entity;
import com.zenith.cache.data.inventory.Container;
import com.zenith.feature.queue.Queue;
import com.zenith.feature.tasks.CommandAction;
import com.zenith.feature.tasks.EventCondition;
import com.zenith.feature.tasks.IntervalCondition;
import com.zenith.feature.tasks.OnceContinuation;
import com.zenith.mc.item.ItemRegistry;
import com.zenith.module.impl.ReplayMod;
import com.zenith.module.impl.Tasks;
import com.zenith.network.client.ClientCookies;
import com.zenith.network.client.ClientSession;
import com.zenith.network.client.CoreRegistryData;
import com.zenith.network.server.ServerSession;
import com.zenith.util.ComponentSerializer;
import org.geysermc.mcprotocollib.auth.GameProfile;
import org.geysermc.mcprotocollib.protocol.codec.MinecraftCodec;
import org.geysermc.mcprotocollib.protocol.data.game.PlayerListEntry;
import org.geysermc.mcprotocollib.protocol.data.game.entity.player.HandPreference;
import org.geysermc.mcprotocollib.protocol.data.game.item.component.DataComponentTypes;

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
 * GET  /v1/details  - inventory, pathfinder, replay and tasks, for the web UI's controls
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
        server.createContext("/v1/details", ex -> handle(ex, "GET", this::details));
        server.createContext("/v1/shutdown", ex -> handle(ex, "POST", this::shutdown));
        server.createContext("/v1/events", this::events);
        server.createContext("/v1/config", ex -> handle(ex, ex.getRequestMethod().equals("POST") ? "POST" : "GET",
            ex.getRequestMethod().equals("POST") ? this::updateConfig : this::config));
        EVENT_BUS.subscribe(
            this,
            of(ConsoleLogEvent.class, this::onConsoleLog),
            of(MainChannelMessageSentEvent.class, e -> onDiscordMessage("notification", e.embed(), e.message())),
            of(ClientConnectEvent.class, e -> lastDisconnectReason = null),
            of(PlayerConnectedEvent.class, e -> onPlayer("joined", false, e.clientGameProfile(), null)),
            of(SpectatorConnectedEvent.class, e -> onPlayer("joined", true, e.clientGameProfile(), null)),
            of(PlayerDisconnectedEvent.class, e -> onPlayer("left", false, e.clientGameProfile(), e.reason())),
            of(SpectatorDisconnectedEvent.class, e -> onPlayer("left", true, e.clientGameProfile(), null)),
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

    private JsonObject details(HttpExchange ex) {
        var proxy = Proxy.getInstance();
        var json = new JsonObject();
        boolean online = proxy.isConnected() && !proxy.isInQueue();
        json.addProperty("online", online);
        json.addProperty("alive", online && CACHE.getPlayerCache().isAlive());
        json.addProperty("controlled", proxy.hasActivePlayer());
        json.add("players", players());
        if (online) {
            json.add("inventory", inventory());
            json.add("nearby", nearby());
        }
        var pathfinder = new JsonObject();
        boolean pathing = online && BARITONE.isActive();
        pathfinder.addProperty("active", pathing);
        if (pathing) {
            BARITONE.getPathingControlManager().mostRecentInControl()
                .ifPresent(process -> pathfinder.addProperty("process", process.displayName()));
        }
        json.add("pathfinder", pathfinder);
        json.addProperty("recording", MODULE.get(ReplayMod.class).isEnabled());
        var click = CONFIG.client.extra.click;
        if (click.holdLeftClick || click.holdRightClick) json.addProperty("clicking", click.holdLeftClick ? "left" : "right");
        var tasks = new JsonArray();
        for (var task : MODULE.get(Tasks.class).getTasks()) {
            var t = new JsonObject();
            t.addProperty("id", task.getId());
            switch (task.getCondition()) {
                case IntervalCondition c -> {
                    boolean once = task.getContinuation() instanceof OnceContinuation;
                    t.addProperty("type", once ? "timed" : "interval");
                    t.addProperty("intervalSeconds", c.getInterval().toSeconds());
                    t.addProperty("next", c.getLastT().plus(c.getInterval()).toEpochMilli());
                }
                case EventCondition c -> {
                    t.addProperty("type", "event");
                    t.addProperty("event", c.getEvent().getSimpleName());
                    t.addProperty("repeat", !(task.getContinuation() instanceof OnceContinuation));
                }
                default -> t.addProperty("type", "other");
            }
            if (task.getAction() instanceof CommandAction action) t.addProperty("command", action.getCommand());
            tasks.add(t);
        }
        json.add("tasks", tasks);
        json.addProperty("tasksEnabled", CONFIG.client.extra.tasks.enabled);
        var client = proxy.getClient();
        if (proxy.isConnected() && client != null) json.add("fingerprint", fingerprint(client));
        return json;
    }

    /** What the server can see of the client the bot pretends to be, for the web UI's privacy card. */
    private JsonObject fingerprint(final ClientSession client) {
        var privacy = CONFIG.client.privacy;
        var json = new JsonObject();
        json.addProperty("brand", "vanilla");
        json.addProperty("clientVersion", MinecraftCodec.CODEC.getMinecraftVersion());
        json.addProperty("serverVersion", client.getProtocolVersion().getName());
        var info = CACHE.getClientInfoCache();
        json.addProperty("locale", info.getLocale());
        json.addProperty("viewDistance", info.getRenderDistance());
        json.addProperty("mainHand", info.getHandPreference() == HandPreference.LEFT_HAND ? "left" : "right");
        json.addProperty("allowsListing", info.isAllowsListing());
        var chat = CACHE.getChatCache();
        json.addProperty("chatSigning", chat.canUseChatSigning() ? "signed" : chat.isEnforcesSecureChat() ? "unavailable" : "off");
        json.addProperty("ping", client.getPing());
        json.addProperty("pingSource", CONFIG.client.ping.sendPingRequests ? "requests" : "tablist");
        json.addProperty("tickAlignedReplies", privacy.humanResponseDelay);
        json.addProperty("knownPacks", CoreRegistryData.available());
        json.addProperty("cookies", ClientCookies.count());
        json.addProperty("resourcePacks", CACHE.getConfigurationCache().getResourcePacks().size());
        json.addProperty("downloadsResourcePacks", privacy.downloadResourcePacks);
        return json;
    }

    /** Other players the bot can see, closest first. */
    private JsonArray nearby() {
        var self = CACHE.getPlayerCache().getThePlayer();
        var players = CACHE.getEntityCache().getPlayers().values().stream()
            .filter(p -> !p.isSelfPlayer() && !p.getUuid().equals(self.getUuid()))
            .sorted(java.util.Comparator.comparingDouble(p -> distanceSq(self, p)))
            .toList();
        var nearby = new JsonArray();
        for (var p : players) {
            var json = new JsonObject();
            json.addProperty("name", CACHE.getTabListCache().get(p.getUuid()).map(PlayerListEntry::getName).orElse(null));
            json.addProperty("uuid", p.getUuid().toString());
            json.addProperty("x", p.getX());
            json.addProperty("y", p.getY());
            json.addProperty("z", p.getZ());
            json.addProperty("distance", Math.sqrt(distanceSq(self, p)));
            json.addProperty("friend", PLAYER_LISTS.getFriendsList().contains(p.getUuid()));
            nearby.add(json);
        }
        return nearby;
    }

    private static double distanceSq(Entity a, Entity b) {
        double dx = a.getX() - b.getX(), dy = a.getY() - b.getY(), dz = a.getZ() - b.getZ();
        return dx * dx + dy * dy + dz * dz;
    }

    /** Everyone connected through the proxy, the controlling player first. */
    private JsonArray players() {
        var proxy = Proxy.getInstance();
        var players = new JsonArray();
        var controller = proxy.getActivePlayer();
        if (controller != null) players.add(player(controller, false));
        proxy.getSpectatorConnections().forEach(s -> players.add(player(s, true)));
        return players;
    }

    private JsonObject player(ServerSession session, boolean spectator) {
        var json = new JsonObject();
        json.addProperty("name", session.getName());
        json.addProperty("spectator", spectator);
        var profile = session.getProfileCache().getProfile();
        json.addProperty("uuid", profile != null ? profile.getId().toString() : null);
        // what the controller command checks before swapping
        json.addProperty("canPlay", profile != null && PLAYER_LISTS.getWhitelist().contains(profile.getId()));
        json.addProperty("canSwap", session.getProtocolVersion().newerThanOrEqualTo(ProtocolVersion.v1_20_5));
        json.addProperty("version", session.getProtocolVersion().getName());
        return json;
    }

    /** The open container (the player's own inventory when none is open), slot by slot. */
    private JsonObject inventory() {
        var inventoryCache = CACHE.getPlayerCache().getInventoryCache();
        var container = inventoryCache.getOpenContainer();
        var json = new JsonObject();
        boolean playerInventory = container.getContainerId() == 0;
        json.addProperty("containerId", container.getContainerId());
        json.addProperty("title", playerInventory ? null : ComponentSerializer.serializePlain(container.getTitle()));
        int size = container.getSize();
        json.addProperty("size", size);
        int hotbarStart = playerInventory ? 36 : size - 9;
        json.addProperty("hotbarStart", hotbarStart);
        json.addProperty("heldSlot", hotbarStart + CACHE.getPlayerCache().getHeldItemSlot());
        var slots = new JsonArray();
        for (int i = 0; i < size; i++) {
            var stack = container.getItemStack(i);
            if (stack == Container.EMPTY_STACK) {
                slots.add(com.google.gson.JsonNull.INSTANCE);
                continue;
            }
            var item = new JsonObject();
            var data = ItemRegistry.REGISTRY.get(stack.getId());
            item.addProperty("id", data != null ? data.name() : "unknown");
            item.addProperty("count", stack.getAmount());
            var customName = stack.getDataComponentsOrEmpty().get(DataComponentTypes.CUSTOM_NAME);
            if (customName != null) item.addProperty("name", ComponentSerializer.serializePlain(customName));
            slots.add(item);
        }
        json.add("slots", slots);
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

    /** Someone joined or left through the proxy, for the manager's connection history. */
    private void onPlayer(String event, boolean spectator, GameProfile profile, String reason) {
        if (eventSubscribers.isEmpty() || profile == null) return;
        var json = new JsonObject();
        json.addProperty("type", "player");
        json.addProperty("ts", System.currentTimeMillis());
        json.addProperty("event", event);
        json.addProperty("name", profile.getName());
        json.addProperty("uuid", profile.getIdAsString());
        json.addProperty("spectator", spectator);
        if (reason != null && !reason.isBlank()) json.addProperty("reason", reason);
        var msg = COMPACT_GSON.toJson(json);
        for (var queue : eventSubscribers) {
            queue.offer(msg);
        }
    }

    private static String stripPrefix(String line) {
        return LOG_PREFIX.matcher(line).replaceFirst("").stripTrailing();
    }

    private static JsonObject embedToJson(Embed embed) {
        // keep within Discord's limits, the manager posts these as they are
        Embed.truncateEmbed(embed);
        var json = new JsonObject();
        json.addProperty("title", embed.title());
        json.addProperty("description", embed.description());
        json.addProperty("url", embed.url());
        if (embed.color() != null) {
            json.addProperty("color", embed.color().getRGB() & 0xFFFFFF);
            // commands report failures with the theme's error colour; lets the web UI tell them apart
            json.addProperty("error", (embed.color().getRGB() & 0xFFFFFF) == (CONFIG.theme.error.rgb() & 0xFFFFFF));
        }
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
