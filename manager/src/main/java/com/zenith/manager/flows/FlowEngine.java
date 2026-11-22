package com.zenith.manager.flows;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.zenith.manager.EventHub;
import com.zenith.manager.auth.Capability;
import com.zenith.manager.instances.Actions;
import com.zenith.manager.util.Json;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Instant;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.LongSupplier;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Flows: block-built automations that run in the manager. A trigger (an in-game event, a change in the bot's
 * status, a timer, a time of day, a broadcast from another flow, or the Run button) starts a list of blocks.
 *
 * A flow runs as the person who last saved it, and every block checks that person's capabilities right
 * when it runs, so losing a grant stops their flows from doing what they no longer may. One run per flow at
 * a time: a new trigger is ignored, or restarts the run (mode "restart"). Flows are kept in
 * {@code manager/flows/<instance>.json}, their last runs and variables in {@code <instance>.state.json}.
 */
public final class FlowEngine {
    private static final Logger LOG = LoggerFactory.getLogger("Flows");
    static final int MAX_FLOWS = 30;
    /** Blocks one run may execute, so a loop without waits can't spin forever. */
    static final int MAX_EXECUTED = 10_000;
    /** Runs at once per instance. */
    static final int MAX_RUNNING = 10;
    static final int KEEP_RUNS = 20;
    static final int LOG_LINES = 60;
    /** "say" and "whisper": at most this many messages per {@link #CHAT_WINDOW_MS}, so servers don't kick for spam. */
    static final int CHAT_LIMIT = 6;
    static final long CHAT_WINDOW_MS = 10_000;
    private static final Pattern PLACEHOLDER = Pattern.compile("\\{([a-zA-Z][a-zA-Z0-9_]*(?::[a-z0-9_]{1,64})?)}");
    /** Web requests per instance per minute. */
    static final int WEBHOOK_LIMIT = 20;
    /** Variables per scope. */
    static final int MAX_VARIABLES = 100;
    /** "run another flow" can go this many flows deep. */
    static final int MAX_CALL_DEPTH = 3;
    /** The key of the variables every flow of a bot shares, next to the per-flow ones (flow ids can't contain *). */
    static final String BOT_VARS = "*bot";

    /** What the engine needs from the rest of the manager; a fake in tests. */
    public interface Bot {
        JsonObject command(String instanceId, String command, String actor) throws Exception;

        void loadPearl(String instanceId, String pearl, String actor) throws Exception;

        void connect(String instanceId, String actor) throws Exception;

        void disconnect(String instanceId, String actor) throws Exception;

        /** The bot's last {@code /v1/status}, or null while it isn't running. */
        @Nullable JsonObject status(String instanceId);

        /** {@code /v1/details} (inventory and the like), or null while it isn't running. */
        @Nullable JsonObject details(String instanceId) throws Exception;

        /** The server address, for {server}. */
        String server(String instanceId);

        /** Every instance's id, for broadcasts to every bot. */
        List<String> instances();

        /** The instance's name, for {fromBot}. */
        String name(String instanceId);

        /** What this user may do on this instance right now; empty if either is gone. */
        Set<Capability> capabilities(String instanceId, String userId);

        /** {@code /v1/block}: {loaded, block}, or null while the bot isn't running. */
        default @Nullable JsonObject block(String instanceId, int x, int y, int z) throws Exception {
            return null;
        }

        /** The relay this instance's outbound requests go through, or null to connect directly. */
        default java.net.@Nullable InetSocketAddress proxy(String instanceId) {
            return null;
        }

        /** Admins may send web requests into the local network. */
        default boolean isAdmin(String userId) {
            return false;
        }
    }

    private final Path dir;
    private final Bot bot;
    private final EventHub events;
    private final LongSupplier clock;
    private final Map<String, List<JsonObject>> flows = new ConcurrentHashMap<>();
    private final Map<String, Run> runs = new ConcurrentHashMap<>();
    /** per instance: flow id → last runs, newest first */
    private final Map<String, Map<String, Deque<JsonObject>>> history = new ConcurrentHashMap<>();
    /** per instance: flow id → variables */
    private final Map<String, Map<String, Map<String, String>>> variables = new ConcurrentHashMap<>();
    private final Map<String, Long> lastStart = new ConcurrentHashMap<>();
    private final Map<String, Long> nextDaily = new ConcurrentHashMap<>();
    private final Map<String, JsonObject> lastStatus = new ConcurrentHashMap<>();
    private final Map<String, Deque<Long>> chatSent = new ConcurrentHashMap<>();
    private final Map<String, Deque<Long>> webSent = new ConcurrentHashMap<>();
    /** per flow: when its trigger happened lately, for "only after N times in M seconds" */
    private final Map<String, Deque<Long>> bursts = new ConcurrentHashMap<>();
    /** per instance and position: the block last seen there */
    private final Map<String, String> lastBlocks = new ConcurrentHashMap<>();
    /** per owner: the variables all their bots' flows share */
    private final Map<String, Map<String, String>> shared = new ConcurrentHashMap<>();
    private volatile boolean sharedLoaded;
    private final List<Waiter> waiters = new CopyOnWriteArrayList<>();

    private record Waiter(String instanceId, JsonObject trigger, CompletableFuture<JsonObject> future) {}

    private static final class Run {
        final String instanceId;
        final JsonObject flow;
        final String actor;
        final long startedAt;
        final String cause;
        final List<String> log = new ArrayList<>();
        volatile Thread thread;
        volatile String step;
        volatile boolean paused;
        int executed;
        final int depth;
        /** {result}, {error}, {each}…: what the blocks so far left behind */
        final Map<String, String> locals = new ConcurrentHashMap<>();
        /** step id → milliseconds spent in it */
        final Map<String, Long> timings = new ConcurrentHashMap<>();
        /** the trigger's details, then each "wait until"'s, for {player}, {message}… */
        JsonObject data;

        Run(String instanceId, JsonObject flow, JsonObject data, long now) {
            this.instanceId = instanceId;
            this.flow = flow;
            this.data = data;
            this.startedAt = now;
            this.actor = "flow:" + flow.get("name").getAsString();
            this.cause = describe(data);
            this.depth = data.has("depth") ? data.get("depth").getAsInt() : 0;
        }

        String id() {
            return flow.get("id").getAsString();
        }
    }

    private static final class Stopped extends RuntimeException {
        Stopped() {
            super(null, null, false, false);
        }
    }

    /** Too many blocks: not something "try" or "keep going" may swallow. */
    private static final class TooMany extends IllegalStateException {
        TooMany() {
            super("Ran more than " + MAX_EXECUTED + " blocks, stopped. Add a wait to loops.");
        }
    }

    public FlowEngine(Path dir, Bot bot, EventHub events) {
        this(dir, bot, events, System::currentTimeMillis);
    }

    FlowEngine(Path dir, Bot bot, EventHub events, LongSupplier clock) {
        this.dir = dir;
        this.bot = bot;
        this.events = events;
        this.clock = clock;
        events.subscribe(this::onEvent);
    }

    /** Loads every instance's flows and starts the timers. */
    public void start() {
        try (var files = Files.list(dir)) {
            files.map(p -> p.getFileName().toString()).filter(n -> n.endsWith(".json") && !n.endsWith(".state.json") && !n.endsWith(".vars.json"))
                .forEach(n -> load(n.substring(0, n.length() - 5)));
        } catch (IOException ignored) {
            // no flows yet
        }
        Thread.ofVirtual().name("flows-timer").start(() -> {
            while (true) {
                try {
                    Thread.sleep(5000);
                    tick(clock.getAsLong());
                } catch (InterruptedException e) {
                    return;
                } catch (Exception e) {
                    LOG.warn("Flow timer failed", e);
                }
            }
        });
    }

    // --- API ---

    public JsonArray list(String instanceId) {
        var out = new JsonArray();
        for (var flow : load(instanceId)) {
            var json = flow.deepCopy();
            var id = flow.get("id").getAsString();
            var run = runs.get(key(instanceId, id));
            if (run != null) {
                var r = new JsonObject();
                r.addProperty("startedAt", run.startedAt);
                r.addProperty("step", run.step);
                r.addProperty("paused", run.paused);
                json.add("running", r);
            }
            var last = runsOf(instanceId, id).peekFirst();
            if (last != null) json.add("last", summary(last));
            var next = nextDaily.get(key(instanceId, id));
            if (next != null && flow.get("enabled").getAsBoolean()) json.addProperty("next", next);
            out.add(json);
        }
        return out;
    }

    /** A flow's last runs (with their log) and its variables. */
    public JsonObject activity(String instanceId, String flowId) {
        if (find(instanceId, flowId) == null) throw new IllegalArgumentException("No such flow");
        var json = new JsonObject();
        var list = new JsonArray();
        synchronized (this) {
            runsOf(instanceId, flowId).forEach(r -> list.add(r.deepCopy()));
        }
        json.add("runs", list);
        var vars = new JsonObject();
        varsOf(instanceId, flowId).forEach(vars::addProperty);
        json.add("variables", vars);
        var botVars = new JsonObject();
        varsOf(instanceId, BOT_VARS).forEach(botVars::addProperty);
        json.add("botVariables", botVars);
        var flow = find(instanceId, flowId);
        var sharedVars = new JsonObject();
        if (flow != null && flow.has("ownerId")) sharedOf(flow.get("ownerId").getAsString()).forEach(sharedVars::addProperty);
        json.add("sharedVariables", sharedVars);
        return json;
    }

    /** Adds or replaces a flow. The saver must be able to do everything in it; it runs as them from now on. */
    public JsonObject save(String instanceId, JsonObject body, String userId, String userName) {
        var checked = FlowSpec.check(body);
        var caps = bot.capabilities(instanceId, userId);
        for (var need : checked.needs()) {
            if (!caps.contains(need)) throw new SecurityException("You can't use " + need.name().toLowerCase(Locale.ROOT) + " blocks on this instance");
        }
        var flow = checked.flow();
        flow.addProperty("ownerId", userId);
        flow.addProperty("ownerName", userName);
        flow.addProperty("updated", clock.getAsLong());
        var id = flow.get("id").getAsString();
        JsonObject before;
        synchronized (this) {
            var list = new ArrayList<>(load(instanceId));
            int at = indexOf(list, id);
            before = at >= 0 ? list.get(at) : null;
            if (at >= 0) list.set(at, flow);
            else if (list.size() >= MAX_FLOWS) throw new IllegalArgumentException("An instance can have at most " + MAX_FLOWS + " flows");
            else list.add(flow);
            persist(instanceId, list);
        }
        var key = key(instanceId, id);
        // a timer counts from when it was saved
        if (before == null || !before.get("trigger").equals(flow.get("trigger"))) lastStart.put(key, clock.getAsLong());
        schedule(instanceId, flow);
        // a run of the old blocks would be confusing; switching it off or on alone doesn't change the blocks
        boolean blocksChanged = before != null && !before.get("steps").equals(flow.get("steps"));
        if (!flow.get("enabled").getAsBoolean() || blocksChanged) stop(instanceId, id);
        changed(instanceId);
        return flow;
    }

    public void delete(String instanceId, String flowId) {
        synchronized (this) {
            var list = new ArrayList<>(load(instanceId));
            if (!list.removeIf(f -> f.get("id").getAsString().equals(flowId))) throw new IllegalArgumentException("No such flow");
            persist(instanceId, list);
            historyOf(instanceId).remove(flowId);
            variablesOf(instanceId).remove(flowId);
            persistState(instanceId);
        }
        stop(instanceId, flowId);
        nextDaily.remove(key(instanceId, flowId));
        changed(instanceId);
    }

    /** The Run button: runs it now, whatever its trigger, even when it's switched off. */
    public void runNow(String instanceId, String flowId) {
        runNow(instanceId, flowId, null);
    }

    /**
     * The Run button, or a test run with made-up event values ({@code {player: "Steve", message: "hi"}}), so a
     * flow can be tried without waiting for its trigger.
     */
    public void runNow(String instanceId, String flowId, @Nullable JsonObject sample) {
        var flow = find(instanceId, flowId);
        if (flow == null) throw new IllegalArgumentException("No such flow");
        var data = new JsonObject();
        if (sample != null) {
            for (var e : sample.entrySet()) {
                if (!e.getKey().matches("[a-zA-Z][a-zA-Z0-9]{0,23}") || e.getKey().equals("event") || e.getKey().equals("depth")) continue;
                if (!e.getValue().isJsonPrimitive()) continue;
                var value = e.getValue().getAsString();
                if (value.length() > 100) value = value.substring(0, 100);
                if (e.getValue().getAsJsonPrimitive().isNumber()) data.addProperty(e.getKey(), e.getValue().getAsDouble());
                else if (e.getValue().getAsJsonPrimitive().isBoolean()) data.addProperty(e.getKey(), e.getValue().getAsBoolean());
                else data.addProperty(e.getKey(), value);
            }
        }
        data.addProperty("event", sample != null ? "test" : "manual");
        if (!launch(instanceId, flow, data, true)) throw new IllegalStateException("It's already running, or too many flows are running");
    }

    /** Holds a run before its next block (a wait that's going on finishes first), or lets it go on. */
    public void pause(String instanceId, String flowId, boolean paused) {
        var run = runs.get(key(instanceId, flowId));
        if (run == null) throw new IllegalStateException("It isn't running");
        run.paused = paused;
        publish(run, paused ? "paused" : "resumed", null);
    }

    public void stop(String instanceId, String flowId) {
        var run = runs.get(key(instanceId, flowId));
        if (run != null && run.thread != null) run.thread.interrupt();
    }

    public void clearVariables(String instanceId, String flowId) {
        synchronized (this) {
            variablesOf(instanceId).remove(flowId);
            persistState(instanceId);
        }
    }

    /** The instance was deleted. */
    public void forget(String instanceId) {
        for (var flow : load(instanceId)) {
            stop(instanceId, flow.get("id").getAsString());
            nextDaily.remove(key(instanceId, flow.get("id").getAsString()));
        }
        flows.remove(instanceId);
        history.remove(instanceId);
        variables.remove(instanceId);
        lastStatus.remove(instanceId);
        try {
            Files.deleteIfExists(file(instanceId));
            Files.deleteIfExists(stateFile(instanceId));
        } catch (IOException e) {
            LOG.warn("Couldn't delete flows of {}", instanceId, e);
        }
    }

    // --- triggers ---

    void onEvent(EventHub.Event event) {
        var instanceId = event.instanceId();
        if (instanceId == null) return;
        if (event.type().equals("game")) {
            var data = event.payload();
            // "mentions me" needs the bot's own name
            var kind = str(data, "event");
            if (kind.equals("chat") || kind.equals("whisper")) {
                data = data.deepCopy();
                var status = bot.status(instanceId);
                if (status != null) data.addProperty("me", str(status, "username"));
            }
            dispatch(instanceId, data);
        } else if (event.type().equals("runtime")) {
            for (var data : changes(instanceId, event.payload())) dispatch(instanceId, data);
        }
    }

    /**
     * Turns changes in the bot's status into events: joined, left, crashed, queue, food, controlled, afk, onlineTime,
     * unattended, moved, proxyJoin and proxyLeave.
     */
    List<JsonObject> changes(String instanceId, JsonObject runtime) {
        var out = new ArrayList<JsonObject>();
        var statusElement = runtime.get("status");
        var status = statusElement != null && statusElement.isJsonObject() ? statusElement.getAsJsonObject() : new JsonObject();
        var phase = str(runtime, "phase");
        var now = new JsonObject();
        now.addProperty("phase", phase);
        now.addProperty("online", bool(status, "online"));
        now.addProperty("controlled", bool(status, "controlled"));
        if (bool(status, "inQueue") && status.has("queuePosition")) now.addProperty("queue", num(status, "queuePosition"));
        if (status.has("food")) now.addProperty("food", num(status, "food"));
        var afk = status.get("afkZone");
        if (afk != null && afk.isJsonObject()) {
            now.addProperty("afkState", str(afk.getAsJsonObject(), "state"));
            now.addProperty("earning", bool(afk.getAsJsonObject(), "earning"));
        }
        boolean online = bool(status, "online");
        if (online) now.addProperty("onlineSeconds", num(status, "onlineSeconds"));
        var pos = status.get("position");
        if (online && pos != null && pos.isJsonObject() && pos.getAsJsonObject().has("x")) {
            now.addProperty("x", num(pos.getAsJsonObject(), "x"));
            now.addProperty("y", num(pos.getAsJsonObject(), "y"));
            now.addProperty("z", num(pos.getAsJsonObject(), "z"));
            now.addProperty("dimension", str(pos.getAsJsonObject(), "dimension").toLowerCase(Locale.ROOT).replace("minecraft:", ""));
        }
        // what the bot reports for flows while it's in the game (com.zenith.control.FlowStatus)
        if (online) {
            if (status.has("alive")) now.addProperty("alive", bool(status, "alive"));
            if (status.has("freeSlots")) now.addProperty("freeSlots", num(status, "freeSlots"));
            now.addProperty("container", status.get("container") instanceof JsonObject);
            if (status.has("tps")) now.addProperty("tps", num(status, "tps"));
            if (status.has("raining")) now.addProperty("raining", bool(status, "raining"));
            if (status.get("pathing") instanceof JsonObject p) now.addProperty("pathing", bool(p, "active"));
            for (var k : List.of("entities", "durability")) if (status.get(k) instanceof JsonArray a) now.add(k, a);
            if (status.get("items") instanceof JsonObject items) now.add("items", items);
        }
        // everyone on the proxy: the one playing and the spectators
        var players = new java.util.LinkedHashSet<String>();
        if (!str(status, "controller").isEmpty()) players.add(str(status, "controller"));
        var spectators = status.get("spectators");
        if (spectators != null && spectators.isJsonArray()) for (var p : spectators.getAsJsonArray()) if (p.isJsonPrimitive()) players.add(p.getAsString());
        var list = new JsonArray();
        players.forEach(list::add);
        now.add("players", list);
        var before = lastStatus.get(instanceId);
        // how long nobody has played, counted from joining or from the last player leaving
        if (online && !bool(status, "controlled")) {
            now.addProperty("idleSince", before != null && before.has("idleSince") ? num(before, "idleSince") : clock.getAsLong());
        }
        now.addProperty("checkedAt", clock.getAsLong());
        lastStatus.put(instanceId, now);
        if (before == null) return out;

        if (bool(before, "online") != bool(now, "online")) {
            var e = event(bool(now, "online") ? "joined" : "left");
            if (!bool(now, "online")) {
                e.addProperty("message", str(status, "lastDisconnectReason"));
                e.addProperty("why", why(status));
            }
            out.add(e);
        }
        if (phase.equals("BACKOFF") && !str(before, "phase").equals("BACKOFF")) {
            var e = event("crashed");
            e.addProperty("message", str(runtime, "lastError"));
            out.add(e);
        }
        if (bool(before, "controlled") != bool(now, "controlled")) {
            var e = event("controlled");
            e.addProperty("on", bool(now, "controlled") ? "start" : "stop");
            e.addProperty("player", str(status, "controller"));
            out.add(e);
        }
        if (now.has("queue")) {
            var e = event("queue");
            e.addProperty("position", num(now, "queue"));
            e.addProperty("previous", before.has("queue") ? num(before, "queue") : Double.MAX_VALUE);
            out.add(e);
        }
        if (now.has("food") && before.has("food") && num(now, "food") != num(before, "food")) {
            var e = event("food");
            e.addProperty("food", num(now, "food"));
            e.addProperty("previous", num(before, "food"));
            out.add(e);
        }
        boolean inZone = str(now, "afkState").equals("IN_ZONE");
        boolean wasInZone = str(before, "afkState").equals("IN_ZONE");
        if (inZone != wasInZone) out.add(afkEvent(inZone ? "enteredZone" : "leftZone"));
        if (bool(now, "earning") != bool(before, "earning")) out.add(afkEvent(bool(now, "earning") ? "earningStarted" : "earningStopped"));
        if (now.has("onlineSeconds")) {
            var e = event("onlineTime");
            e.addProperty("seconds", num(now, "onlineSeconds"));
            e.addProperty("previous", before.has("onlineSeconds") ? num(before, "onlineSeconds") : -1);
            out.add(e);
        }
        if (now.has("idleSince")) {
            var e = event("unattended");
            e.addProperty("seconds", (clock.getAsLong() - num(now, "idleSince")) / 1000);
            e.addProperty("previous", before.has("idleSince") ? (lastIdleCheck(before) - num(now, "idleSince")) / 1000 : -1);
            out.add(e);
        }
        if (now.has("x") && before.has("x") && (num(now, "x") != num(before, "x") || num(now, "y") != num(before, "y") || num(now, "z") != num(before, "z"))) {
            var e = event("moved");
            e.addProperty("x", num(now, "x"));
            e.addProperty("y", num(now, "y"));
            e.addProperty("z", num(now, "z"));
            e.addProperty("previousX", num(before, "x"));
            e.addProperty("previousY", num(before, "y"));
            e.addProperty("previousZ", num(before, "z"));
            out.add(e);
        }
        if (!str(now, "dimension").isEmpty() && !str(before, "dimension").isEmpty() && !str(now, "dimension").equals(str(before, "dimension"))) {
            var e = event("dimension");
            e.addProperty("dimension", str(now, "dimension"));
            e.addProperty("previous", str(before, "dimension"));
            out.add(e);
        }
        if (now.has("alive") && before.has("alive") && bool(now, "alive") && !bool(before, "alive")) out.add(event("respawned"));
        if (now.has("freeSlots") && before.has("freeSlots") && num(now, "freeSlots") == 0 && num(before, "freeSlots") > 0) out.add(event("inventoryFull"));
        if (online && bool(before, "online") && bool(now, "container") != bool(before, "container")) {
            var e = event(bool(now, "container") ? "containerOpened" : "containerClosed");
            if (status.get("container") instanceof JsonObject c) e.addProperty("message", str(c, "title"));
            out.add(e);
        }
        if (now.has("tps") && before.has("tps") && num(now, "tps") != num(before, "tps")) {
            var e = event("tps");
            e.addProperty("tps", num(now, "tps"));
            e.addProperty("previous", num(before, "tps"));
            out.add(e);
        }
        if (now.has("raining") && before.has("raining") && bool(now, "raining") != bool(before, "raining")) {
            var e = event("weather");
            e.addProperty("on", bool(now, "raining") ? "rainStart" : "rainStop");
            out.add(e);
        }
        if (bool(before, "pathing") && now.has("pathing") && !bool(now, "pathing")) out.add(event("pathDone"));
        for (var k : List.of("entities", "durability", "items")) {
            if (now.has(k) && before.has(k) && !now.get(k).equals(before.get(k))) {
                var e = event(k);
                e.add("now", now.get(k));
                e.add("before", before.get(k));
                out.add(e);
            }
        }
        var had = new java.util.HashSet<String>();
        if (before.has("players")) for (var p : before.getAsJsonArray("players")) had.add(p.getAsString());
        for (var p : players) if (!had.contains(p)) out.add(proxyEvent("proxyJoin", p));
        for (var p : had) if (!players.contains(p)) out.add(proxyEvent("proxyLeave", p));
        return out;
    }

    /** Why the bot left: banned, the connection broke, we disconnected it ourselves, or the server kicked it. */
    static String why(JsonObject status) {
        if (status.get("ban") instanceof JsonObject) return "banned";
        var reason = str(status, "lastDisconnectReason");
        if (reason.isEmpty() || reason.equals("Max Playtime Disconnect") || reason.equals("System Disconnect")) return "me";
        var lower = reason.toLowerCase(Locale.ROOT);
        for (var network : List.of("connection reset", "timed out", "didn't respond in time", "broken pipe", "no route to host", "can't be reached",
            "refused the connection", "proxyconnect", "couldn't be found", "connection lost", "end of stream")) {
            if (lower.contains(network)) return "network";
        }
        if (lower.contains("banned")) return "banned";
        return "kicked";
    }

    private long lastIdleCheck(JsonObject before) {
        return before.has("checkedAt") ? (long) num(before, "checkedAt") : clock.getAsLong();
    }

    private static JsonObject proxyEvent(String kind, String player) {
        var e = event(kind);
        e.addProperty("player", player);
        return e;
    }

    private static JsonObject afkEvent(String on) {
        var e = event("afk");
        e.addProperty("on", on);
        return e;
    }

    private void dispatch(String instanceId, JsonObject data) {
        for (var w : waiters) {
            if (!w.instanceId.equals(instanceId)) continue;
            var hit = hit(w.trigger, data);
            if (hit != null) w.future.complete(hit);
        }
        for (var flow : load(instanceId)) {
            if (!flow.get("enabled").getAsBoolean()) continue;
            // a failing flow doesn't start itself again
            if (str(data, "event").equals("flowFailed") && str(data, "flowId").equals(str(flow, "id"))) continue;
            var hit = hit(flow.getAsJsonObject("trigger"), data);
            if (hit != null) launch(instanceId, flow, hit, false);
        }
    }

    /**
     * Whether an event starts a trigger, and with what details. Entities, items and durability come as whole
     * lists (now and before); these find the entity or item that crossed the trigger's line.
     */
    static @Nullable JsonObject hit(JsonObject trigger, JsonObject data) {
        var kind = str(trigger, "kind");
        var event = str(data, "event");
        switch (kind) {
            case "entityNear", "entityLeft" -> {
                if (!event.equals("entities")) return null;
                var before = near(trigger, data.get("before"));
                var now = near(trigger, data.get("now"));
                var from = kind.equals("entityNear") ? now : before;
                var other = kind.equals("entityNear") ? before : now;
                for (var e : from.entrySet()) {
                    if (other.containsKey(e.getKey())) continue;
                    var out = entityData(e.getValue());
                    out.addProperty("event", kind);
                    return out;
                }
                return null;
            }
            case "itemBelow", "itemAbove" -> {
                if (!event.equals("items")) return null;
                var item = str(trigger, "item");
                double count = trigger.get("count").getAsDouble();
                double now = itemCount(data.get("now"), item), before = itemCount(data.get("before"), item);
                boolean crossed = kind.equals("itemBelow") ? now < count && before >= count : now > count && before <= count;
                if (!crossed) return null;
                var out = event(kind);
                out.addProperty("item", item);
                out.addProperty("count", now);
                out.addProperty("previous", before);
                return out;
            }
            case "durability" -> {
                if (!event.equals("durability") || !(data.get("now") instanceof JsonArray now) || !(data.get("before") instanceof JsonArray before)) return null;
                double mark = trigger.get("percent").getAsDouble();
                for (var n : now) {
                    if (!(n instanceof JsonObject item)) continue;
                    for (var b : before) {
                        if (!(b instanceof JsonObject was) || !str(was, "slot").equals(str(item, "slot")) || !str(was, "item").equals(str(item, "item"))) continue;
                        double pct = percent(item), previous = percent(was);
                        if (pct < mark && previous >= mark) {
                            var out = event("durability");
                            out.addProperty("item", str(item, "item"));
                            out.addProperty("slot", str(item, "slot"));
                            out.addProperty("count", num(item, "left"));
                            out.addProperty("percent", Math.floor(pct));
                            return out;
                        }
                    }
                }
                return null;
            }
            default -> {
                return matches(trigger, data) ? data : null;
            }
        }
    }

    private static double percent(JsonObject item) {
        return num(item, "max") > 0 ? num(item, "left") * 100 / num(item, "max") : 100;
    }

    private static double itemCount(@Nullable JsonElement items, String item) {
        return items instanceof JsonObject o && o.has(item) ? o.get(item).getAsDouble() : 0;
    }

    /** The entities from a status list that fit an entity filter, by entity id. */
    static Map<String, JsonObject> near(JsonObject filter, @Nullable JsonElement list) {
        var out = new LinkedHashMap<String, JsonObject>();
        if (!(list instanceof JsonArray array)) return out;
        double radius = num(filter, "radius");
        for (var e : array) {
            if (e instanceof JsonObject entity && num(entity, "distance") <= radius && entityFits(filter, entity)) out.put(str(entity, "id"), entity);
        }
        return out;
    }

    static boolean entityFits(JsonObject filter, JsonObject entity) {
        var kind = str(entity, "kind");
        boolean player = kind.equals("player");
        return switch (str(filter, "what")) {
            case "anyone", "" -> player;
            case "stranger" -> player && !bool(entity, "friend");
            case "friend" -> player && bool(entity, "friend");
            case "player" -> player && str(entity, "name").equalsIgnoreCase(str(filter, "name"));
            case "hostile" -> kind.equals("hostile");
            case "mob" -> kind.equals("hostile") || kind.equals("mob");
            case "type" -> str(entity, "type").equals(str(filter, "mob"));
            case "item" -> kind.equals("item");
            case "vehicle" -> kind.equals("vehicle");
            default -> true;
        };
    }

    /** What the blocks can use about an entity: {entity}, {player}, {distance}, {x} {y} {z}. */
    private static JsonObject entityData(JsonObject entity) {
        var out = new JsonObject();
        var name = str(entity, "name");
        out.addProperty("entity", name.isEmpty() ? str(entity, "type").replace('_', ' ') : name);
        if (str(entity, "kind").equals("player") && !name.isEmpty()) out.addProperty("player", name);
        out.addProperty("type", str(entity, "type"));
        out.addProperty("friend", bool(entity, "friend"));
        out.addProperty("distance", Math.round(num(entity, "distance")));
        for (var k : List.of("x", "y", "z")) out.addProperty(k, Math.floor(num(entity, k)));
        return out;
    }

    void tick(long now) {
        for (var entry : flows.entrySet()) {
            var instanceId = entry.getKey();
            var status = bot.status(instanceId);
            boolean online = status != null && bool(status, "online");
            if (online) watchBlocks(instanceId, entry.getValue());
            for (var flow : entry.getValue()) {
                if (!flow.get("enabled").getAsBoolean()) continue;
                var trigger = flow.getAsJsonObject("trigger");
                var key = key(instanceId, flow.get("id").getAsString());
                switch (str(trigger, "kind")) {
                    // only while in the game: a timer on an offline bot would only fail
                    case "every" -> {
                        if (!online) continue;
                        long every = trigger.get("minutes").getAsLong() * 60_000;
                        long last = lastStart.computeIfAbsent(key, k -> now);
                        if (now - last >= every) {
                            lastStart.put(key, now);
                            launch(instanceId, flow, event("every"), true);
                        }
                    }
                    // also while offline, so a flow can connect the bot at a time of day
                    case "daily" -> {
                        var next = nextDaily.get(key);
                        if (next == null) schedule(instanceId, flow);
                        else if (now >= next) {
                            nextDaily.put(key, nextDaily(trigger, now + 60_000));
                            launch(instanceId, flow, event("daily"), true);
                        }
                    }
                    default -> {}
                }
            }
        }
    }

    /** "a block changes": looks at the watched positions (of triggers and of runs waiting for one). */
    private void watchBlocks(String instanceId, List<JsonObject> list) {
        var positions = new java.util.LinkedHashSet<String>();
        for (var flow : list) {
            var trigger = flow.getAsJsonObject("trigger");
            if (flow.get("enabled").getAsBoolean() && str(trigger, "kind").equals("blockChanged")) positions.add(position(trigger));
        }
        for (var w : waiters) if (w.instanceId.equals(instanceId) && str(w.trigger, "kind").equals("blockChanged")) positions.add(position(w.trigger));
        for (var position : positions) {
            var xyz = position.split(" ");
            JsonObject block;
            try {
                block = bot.block(instanceId, Integer.parseInt(xyz[0]), Integer.parseInt(xyz[1]), Integer.parseInt(xyz[2]));
            } catch (Exception e) {
                continue;
            }
            if (block == null || !bool(block, "loaded")) continue;
            var name = str(block, "block");
            var previous = lastBlocks.put(instanceId + " " + position, name);
            if (previous == null || previous.equals(name)) continue;
            var data = event("blockChanged");
            data.addProperty("x", Integer.parseInt(xyz[0]));
            data.addProperty("y", Integer.parseInt(xyz[1]));
            data.addProperty("z", Integer.parseInt(xyz[2]));
            data.addProperty("block", name);
            data.addProperty("previous", previous);
            dispatch(instanceId, data);
        }
    }

    private static String position(JsonObject trigger) {
        return (long) num(trigger, "x") + " " + (long) num(trigger, "y") + " " + (long) num(trigger, "z");
    }

    private void schedule(String instanceId, JsonObject flow) {
        var key = key(instanceId, flow.get("id").getAsString());
        var trigger = flow.getAsJsonObject("trigger");
        if (str(trigger, "kind").equals("daily")) nextDaily.put(key, nextDaily(trigger, clock.getAsLong()));
        else nextDaily.remove(key);
    }

    /** The next time after {@code after} that a daily trigger fires; missed times are skipped. */
    static long nextDaily(JsonObject trigger, long after) {
        var zone = ZoneId.of(str(trigger, "zone"));
        var at = LocalTime.parse(str(trigger, "at"));
        var days = str(trigger, "days");
        var start = Instant.ofEpochMilli(after).atZone(zone);
        for (int i = 0; i <= 7; i++) {
            ZonedDateTime candidate = start.toLocalDate().plusDays(i).atTime(at).atZone(zone);
            if (candidate.toInstant().toEpochMilli() <= after) continue;
            if (days.indexOf((char) ('0' + candidate.getDayOfWeek().getValue())) >= 0) return candidate.toInstant().toEpochMilli();
        }
        return Long.MAX_VALUE;
    }

    static boolean matches(JsonObject trigger, JsonObject data) {
        var kind = str(trigger, "kind");
        var event = str(data, "event");
        return switch (kind) {
            case "chat", "whisper" -> event.equals(kind)
                && text(trigger, data)
                && (str(trigger, "from").isEmpty() || str(trigger, "from").equalsIgnoreCase(str(data, "from")));
            case "player" -> event.equals("visualRange") && str(data, "action").equals("enter") && who(trigger, data);
            case "playerLeft" -> event.equals("visualRange") && !str(data, "action").equals("enter") && who(trigger, data);
            // crossing the line, not every change below it
            case "health", "food" -> event.equals(kind)
                && num(data, kind) < trigger.get("below").getAsDouble()
                && num(data, "previous") >= trigger.get("below").getAsDouble();
            case "queueBelow" -> event.equals("queue")
                && num(data, "position") < trigger.get("position").getAsDouble()
                && num(data, "previous") >= trigger.get("position").getAsDouble();
            case "left" -> event.equals("left") && contains(str(data, "message"), str(trigger, "reason"))
                && (str(trigger, "why").isEmpty() || str(trigger, "why").equals("any") || str(trigger, "why").equals(str(data, "why")));
            case "controlled", "afk" -> event.equals(kind) && str(data, "on").equals(str(trigger, "on"));
            case "receive" -> event.equals("receive") && str(data, "name").equalsIgnoreCase(str(trigger, "name"));
            // once per session or stretch: when the time passes the mark
            case "onlineFor" -> event.equals("onlineTime") && crossed(data, trigger.get("minutes").getAsDouble() * 60);
            case "unattended" -> event.equals("unattended") && crossed(data, trigger.get("minutes").getAsDouble() * 60);
            case "arrived" -> event.equals("moved")
                && within(trigger, num(data, "x"), num(data, "y"), num(data, "z"))
                && !within(trigger, num(data, "previousX"), num(data, "previousY"), num(data, "previousZ"));
            case "yBelow" -> event.equals("moved") && num(data, "y") < num(trigger, "y") && num(data, "previousY") >= num(trigger, "y");
            case "serverJoin", "serverLeave" -> event.equals(kind) && switch (str(trigger, "who")) {
                case "anyone", "" -> true;
                case "stranger" -> !bool(data, "friend");
                case "friend" -> bool(data, "friend");
                default -> str(trigger, "who").equalsIgnoreCase(str(data, "player"));
            };
            case "dimension" -> event.equals("dimension") && (str(trigger, "to").equals("any") || str(trigger, "to").equals(str(data, "dimension")));
            case "damaged" -> event.equals("health") && num(data, "previous") - num(data, "health") >= num(trigger, "amount");
            case "tpsBelow" -> event.equals("tps") && num(data, "tps") < num(trigger, "tps") && num(data, "previous") >= num(trigger, "tps");
            case "weather" -> event.equals("weather") && str(data, "on").equals(str(trigger, "on"));
            case "blockChanged" -> event.equals("blockChanged")
                && num(data, "x") == num(trigger, "x") && num(data, "y") == num(trigger, "y") && num(data, "z") == num(trigger, "z");
            case "flowFailed" -> event.equals("flowFailed") && (str(trigger, "flow").isEmpty() || str(trigger, "flow").equals(str(data, "flowId")));
            case "entityNear", "entityLeft", "itemBelow", "itemAbove", "durability" -> false;
            case "proxyJoin", "proxyLeave" -> event.equals(kind)
                && (str(trigger, "who").equals("anyone") || str(trigger, "who").isEmpty() || str(trigger, "who").equalsIgnoreCase(str(data, "player")));
            case "manual", "every", "daily" -> false;
            default -> event.equals(kind);
        };
    }

    private static boolean crossed(JsonObject data, double mark) {
        return num(data, "seconds") >= mark && num(data, "previous") < mark;
    }

    /** Within the radius of x/z, or of x/y/z when the trigger has a y. */
    private static boolean within(JsonObject trigger, double x, double y, double z) {
        double dx = x - num(trigger, "x"), dz = z - num(trigger, "z");
        double dy = trigger.has("y") && !Double.isNaN(y) ? y - num(trigger, "y") : 0;
        return Math.sqrt(dx * dx + dy * dy + dz * dz) <= num(trigger, "radius");
    }

    /** Chat text: contains, starts with, is exactly, or mentions the bot's name (and contains the text, if any). */
    private static boolean text(JsonObject trigger, JsonObject data) {
        var message = str(data, "message");
        var part = str(trigger, "contains");
        var lower = message.toLowerCase(Locale.ROOT).strip();
        return switch (str(trigger, "match")) {
            case "starts" -> lower.startsWith(part.toLowerCase(Locale.ROOT));
            case "exact" -> lower.equals(part.toLowerCase(Locale.ROOT).strip());
            case "mention" -> !str(data, "me").isEmpty()
                && Pattern.compile("(?<![A-Za-z0-9_])" + Pattern.quote(str(data, "me")) + "(?![A-Za-z0-9_])", Pattern.CASE_INSENSITIVE).matcher(message).find()
                && contains(message, part);
            default -> contains(message, part);
        };
    }

    private static boolean who(JsonObject trigger, JsonObject data) {
        return switch (str(trigger, "who")) {
            case "anyone", "" -> true;
            case "stranger" -> !bool(data, "friend");
            default -> str(trigger, "who").equalsIgnoreCase(str(data, "player"));
        };
    }

    /** Starts a run unless one is going (or restarts it), it's cooling down, or too many are running. */
    private boolean launch(String instanceId, JsonObject flow, JsonObject data, boolean ignoreCooldown) {
        var key = key(instanceId, flow.get("id").getAsString());
        var run = new Run(instanceId, flow, data, clock.getAsLong());
        Run previous;
        synchronized (this) {
            previous = runs.get(key);
            if (previous != null && !str(flow, "mode").equals("restart")) return false;
            if (previous == null && runs.keySet().stream().filter(k -> k.startsWith(instanceId + "/")).count() >= MAX_RUNNING) {
                LOG.warn("Instance {} already runs {} flows, skipped {}", instanceId, MAX_RUNNING, flow.get("name").getAsString());
                return false;
            }
            if (!ignoreCooldown && flow.get("burst") instanceof JsonObject burst) {
                // only after N triggers within M seconds; those triggers are used up by the run
                var times = bursts.computeIfAbsent(key, k -> new ArrayDeque<>());
                times.addLast(run.startedAt);
                while (!times.isEmpty() && run.startedAt - times.peekFirst() > burst.get("seconds").getAsLong() * 1000) times.removeFirst();
                if (times.size() < burst.get("count").getAsInt()) return false;
                times.clear();
            }
            var last = runsOf(instanceId, flow.get("id").getAsString()).peekFirst();
            long cooldown = flow.get("cooldown").getAsLong() * 1000;
            if (!ignoreCooldown && previous == null && last != null && run.startedAt - last.get("startedAt").getAsLong() < cooldown) return false;
            runs.put(key, run);
        }
        var old = previous;
        run.thread = Thread.ofVirtual().name("flow-" + key).unstarted(() -> {
            if (old != null && old.thread != null) {
                old.thread.interrupt();
                try {
                    old.thread.join(5000);
                } catch (InterruptedException e) {
                    runs.remove(key, run);
                    return;
                }
            }
            execute(key, run);
        });
        run.thread.start();
        return true;
    }

    // --- running ---

    private void execute(String key, Run run) {
        publish(run, "started", null);
        String error = null;
        try {
            steps(run, run.flow.getAsJsonArray("steps"));
        } catch (Stopped e) {
            // a "stop" block
        } catch (InterruptedException e) {
            error = "Stopped";
        } catch (Exception e) {
            error = e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName();
        }
        var result = new JsonObject();
        result.addProperty("ok", error == null);
        if (error != null) result.addProperty("message", error);
        if (error != null && run.step != null) result.addProperty("step", run.step);
        result.addProperty("startedAt", run.startedAt);
        result.addProperty("at", clock.getAsLong());
        result.addProperty("cause", run.cause);
        var log = new JsonArray();
        synchronized (run.log) {
            run.log.forEach(log::add);
        }
        result.add("log", log);
        var timings = new JsonObject();
        run.timings.forEach(timings::addProperty);
        result.add("timings", timings);
        synchronized (this) {
            var list = runsOf(run.instanceId, run.id());
            list.addFirst(result);
            while (list.size() > KEEP_RUNS) list.removeLast();
            persistState(run.instanceId);
            runs.remove(key, run);
        }
        publish(run, error == null ? "done" : "error", error);
        // "a flow fails": not when someone pressed Stop
        if (error != null && !error.equals("Stopped")) {
            var failed = event("flowFailed");
            failed.addProperty("flow", run.flow.get("name").getAsString());
            failed.addProperty("flowId", run.id());
            failed.addProperty("error", error);
            Thread.ofVirtual().start(() -> dispatch(run.instanceId, failed));
        }
    }

    private void steps(Run run, JsonArray steps) throws Exception {
        for (var element : steps) {
            if (Thread.interrupted()) throw new InterruptedException();
            var step = element.getAsJsonObject();
            if (bool(step, "off")) continue;
            while (run.paused) Thread.sleep(250);
            if (++run.executed > MAX_EXECUTED) throw new TooMany();
            var id = step.get("id").getAsString();
            run.step = id;
            publish(run, "step", null);
            long start = clock.getAsLong();
            try {
                step(run, step);
            } catch (Exception e) {
                if (!bool(step, "keepGoing") || !catchable(e)) throw e;
                var message = message(e);
                run.locals.put("error", message);
                run.locals.put("result", "failed");
                log(run, "Failed, kept going: " + message);
            } finally {
                run.timings.merge(id, clock.getAsLong() - start, Long::sum);
            }
        }
    }

    /** Errors "try" and "keep going" handle; a Stop, the Stop button and runaway loops still end the run. */
    private static boolean catchable(Exception e) {
        return !(e instanceof InterruptedException || e instanceof Stopped || e instanceof TooMany);
    }

    private static String message(Exception e) {
        return e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName();
    }

    private void step(Run run, JsonObject step) throws Exception {
        var id = run.instanceId;
        switch (step.get("type").getAsString()) {
            case "action" -> {
                var action = Actions.parse(fill(run, step.getAsJsonObject("run")));
                need(run, action.capability());
                if (action.command().startsWith("pearlLoader load ")) {
                    bot.loadPearl(id, action.command().substring("pearlLoader load ".length()), run.actor);
                } else {
                    checkResult(bot.command(id, action.command(), run.actor));
                }
                var wait = str(step, "wait");
                if (!wait.isEmpty() && !wait.equals("no")) awaitPathing(run, wait, step.get("timeout").getAsLong());
            }
            case "reconnect" -> {
                need(run, Capability.CONTROL);
                if (bot.status(id) != null) checkResult(bot.command(id, "disconnect", run.actor));
                long seconds = step.get("seconds").getAsLong();
                long upTo = step.has("upTo") ? step.get("upTo").getAsLong() : seconds;
                long ms = seconds * 1000 + (upTo > seconds ? ThreadLocalRandom.current().nextLong((upTo - seconds) * 1000 + 1) : 0);
                if (ms > 0) log(run, "Reconnecting in " + number(Math.round(ms / 100.0) / 10.0) + " s");
                Thread.sleep(ms);
                long since = clock.getAsLong();
                bot.connect(id, run.actor);
                long timeout = step.get("timeout").getAsLong();
                if (timeout > 0) awaitOnline(run, since, timeout);
            }
            case "try" -> {
                try {
                    steps(run, step.getAsJsonArray("steps"));
                    run.locals.remove("error");
                } catch (Exception e) {
                    if (!catchable(e)) throw e;
                    run.locals.put("error", message(e));
                    log(run, "Failed: " + message(e));
                    steps(run, step.getAsJsonArray("catch"));
                }
            }
            case "forEach" -> {
                for (var each : list(run, str(step, "list"))) {
                    run.locals.putAll(each);
                    steps(run, step.getAsJsonArray("steps"));
                }
            }
            case "runFlow" -> runFlow(run, step);
            case "webhook" -> webhook(run, step);
            case "holdItem" -> {
                need(run, Capability.PLAY);
                holdItem(run, str(step, "item"));
            }
            case "dropItem" -> {
                need(run, Capability.PLAY);
                dropItem(run, str(step, "item"), bool(step, "all"));
            }
            case "lookAt" -> {
                need(run, Capability.PLAY);
                lookAt(run, step);
            }
            case "say" -> {
                need(run, Capability.CHAT);
                var template = step.get("text").getAsString();
                var text = render(run, template, 256);
                // a value from someone else's chat must not turn the message into a command
                if (text.startsWith("/") && !template.startsWith("/")) throw new IllegalStateException("The message would have run a command");
                if (text.isBlank()) return;
                chatSlot(id);
                checkResult(bot.command(id, "say " + text, run.actor));
            }
            case "whisper" -> {
                need(run, Capability.CHAT);
                var player = render(run, step.get("player").getAsString(), 40);
                if (!FlowSpec.PLAYER.matcher(player).matches()) throw new IllegalStateException("Not a player to whisper to: " + (player.isEmpty() ? "(empty)" : player));
                var text = render(run, step.get("text").getAsString(), 200);
                if (text.isBlank()) return;
                chatSlot(id);
                checkResult(bot.command(id, "say /msg " + player + " " + text, run.actor));
            }
            case "notify" -> {
                need(run, Capability.CONTROL);
                var text = render(run, step.get("text").getAsString(), 200);
                if (!text.isBlank()) checkResult(bot.command(id, "notify " + text, run.actor));
            }
            case "log" -> log(run, render(run, step.get("text").getAsString(), 200));
            case "connect" -> {
                need(run, Capability.CONTROL);
                long since = clock.getAsLong();
                bot.connect(id, run.actor);
                if (bool(step, "wait")) awaitOnline(run, since, step.get("timeout").getAsLong());
            }
            case "disconnect" -> {
                need(run, Capability.CONTROL);
                bot.disconnect(id, run.actor);
            }
            case "wait" -> {
                long seconds = step.get("seconds").getAsLong();
                long upTo = step.has("upTo") ? step.get("upTo").getAsLong() : seconds;
                long ms = seconds * 1000 + (upTo > seconds ? ThreadLocalRandom.current().nextLong((upTo - seconds) * 1000 + 1) : 0);
                Thread.sleep(ms);
            }
            case "waitFor" -> {
                var waiter = new Waiter(id, step.getAsJsonObject("event"), new CompletableFuture<>());
                waiters.add(waiter);
                try {
                    long timeout = step.get("timeout").getAsLong();
                    run.data = timeout > 0 ? waiter.future.get(timeout, TimeUnit.SECONDS) : waiter.future.get();
                    run.locals.put("result", "done");
                } catch (TimeoutException e) {
                    run.locals.put("result", "timeout");
                    log(run, "Stopped waiting after " + step.get("timeout").getAsLong() + " s");
                } finally {
                    waiters.remove(waiter);
                }
            }
            case "waitUntil" -> {
                long timeout = step.get("timeout").getAsLong() * 1000;
                long until = clock.getAsLong() + timeout;
                run.locals.put("result", "done");
                while (!condition(run, step.getAsJsonObject("cond"))) {
                    if (timeout > 0 && clock.getAsLong() >= until) {
                        run.locals.put("result", "timeout");
                        log(run, "Stopped waiting after " + timeout / 1000 + " s");
                        break;
                    }
                    Thread.sleep(1000);
                }
            }
            case "if" -> steps(run, step.getAsJsonArray(condition(run, step.getAsJsonObject("cond")) ? "then" : "else"));
            case "repeat" -> {
                int times = step.get("times").getAsInt();
                for (int i = 0; i < times; i++) steps(run, step.getAsJsonArray("steps"));
            }
            case "repeatUntil" -> {
                while (!condition(run, step.getAsJsonObject("cond"))) loop(run, step);
            }
            case "forever" -> {
                while (true) loop(run, step);
            }
            // to this bot, one other bot or every bot the owner controls; the receiving flows run as their own owners
            case "broadcast" -> {
                var to = str(step, "to");
                var owner = run.flow.get("ownerId").getAsString();
                var targets = new ArrayList<String>();
                if (to.isEmpty() || to.equals(id)) targets.add(id);
                else if (to.equals("all")) {
                    for (var other : bot.instances()) if (other.equals(id) || bot.capabilities(other, owner).contains(Capability.CONTROL)) targets.add(other);
                } else if (bot.capabilities(to, owner).contains(Capability.CONTROL)) targets.add(to);
                else throw new SecurityException(run.flow.get("ownerName").getAsString() + " can't control that bot anymore, or it was deleted");
                var data = event("receive");
                data.addProperty("name", step.get("name").getAsString());
                data.addProperty("from", run.flow.get("name").getAsString());
                data.addProperty("fromBot", bot.name(id));
                Thread.ofVirtual().start(() -> targets.forEach(t -> dispatch(t, data.deepCopy())));
            }
            case "set" -> setVar(run, str(step, "scope"), step.get("name").getAsString(), render(run, step.get("value").getAsString(), 200));
            case "change" -> {
                var name = step.get("name").getAsString();
                double current;
                try {
                    current = Double.parseDouble(scope(run, str(step, "scope")).getOrDefault(name, "0"));
                } catch (NumberFormatException e) {
                    current = 0;
                }
                setVar(run, str(step, "scope"), name, number(current + step.get("by").getAsDouble()));
            }
            case "stop" -> throw new Stopped();
            default -> throw new IllegalStateException("Unknown block");
        }
    }

    /** One pass of a loop; at least a second each, so a loop without waits doesn't flood the bot. */
    private void loop(Run run, JsonObject step) throws Exception {
        long start = clock.getAsLong();
        steps(run, step.getAsJsonArray("steps"));
        long took = clock.getAsLong() - start;
        if (took < 1000) Thread.sleep(1000 - took);
    }

    /**
     * Waits for the pathfinder: "done" until it stops (or the time runs out, which stops it and gives
     * {result} timeout), "for" a while and then stops it. The status is polled every 2 s, so a short walk can be
     * over before it shows; one that never shows as active within a few seconds counts as done.
     */
    private void awaitPathing(Run run, String wait, long timeoutSeconds) throws Exception {
        long start = clock.getAsLong();
        long limit = timeoutSeconds * 1000;
        boolean seen = false;
        while (true) {
            Thread.sleep(1000);
            var status = bot.status(run.instanceId);
            if (status == null || !bool(status, "online")) throw new IllegalStateException("The bot left the game");
            boolean active = status.get("pathing") instanceof JsonObject p && bool(p, "active");
            long elapsed = clock.getAsLong() - start;
            if (active) seen = true;
            else if (seen || elapsed >= 6000) {
                run.locals.put("result", "done");
                return;
            }
            if (elapsed >= limit) {
                checkResult(bot.command(run.instanceId, "pathfinder stop", run.actor));
                run.locals.put("result", wait.equals("for") ? "done" : "timeout");
                if (wait.equals("done")) log(run, "Still moving after " + timeoutSeconds + " s, stopped it");
                return;
            }
        }
    }

    /** Waits until the bot is in the game; fails if the connection fails or it takes longer than the limit. */
    private void awaitOnline(Run run, long since, long timeoutSeconds) throws Exception {
        long deadline = clock.getAsLong() + timeoutSeconds * 1000;
        while (true) {
            var status = bot.status(run.instanceId);
            if (status != null && bool(status, "online")) {
                run.locals.put("result", "done");
                return;
            }
            if (status != null && status.has("lastDisconnectAt") && num(status, "lastDisconnectAt") >= since && !bool(status, "connected")) {
                run.locals.put("result", "failed");
                throw new IllegalStateException("Couldn't connect: " + str(status, "lastDisconnectReason"));
            }
            if (clock.getAsLong() >= deadline) {
                run.locals.put("result", "timeout");
                throw new IllegalStateException("Not in the game after " + timeoutSeconds + " s" + (status != null && bool(status, "inQueue") ? " (still in the queue)" : ""));
            }
            Thread.sleep(1000);
        }
    }

    /** What "for each" goes through: {each} plus {eachDistance} or {eachCount}. */
    private List<Map<String, String>> list(Run run, String kind) {
        var status = bot.status(run.instanceId);
        var s = status != null ? status : new JsonObject();
        var out = new ArrayList<Map<String, String>>();
        switch (kind) {
            case "nearbyPlayers", "nearbyEntities" -> {
                if (s.get("entities") instanceof JsonArray entities) {
                    for (var e : entities) {
                        if (!(e instanceof JsonObject entity)) continue;
                        boolean player = str(entity, "kind").equals("player");
                        if (kind.equals("nearbyPlayers") && (!player || str(entity, "name").isEmpty())) continue;
                        var name = player && !str(entity, "name").isEmpty() ? str(entity, "name") : str(entity, "type");
                        out.add(Map.of("each", name, "eachDistance", number(Math.round(num(entity, "distance"))), "eachCount", "1"));
                    }
                }
            }
            case "proxyPlayers" -> {
                if (!str(s, "controller").isEmpty()) out.add(Map.of("each", str(s, "controller"), "eachDistance", "", "eachCount", "1"));
                if (s.get("spectators") instanceof JsonArray spectators) {
                    for (var p : spectators) if (p.isJsonPrimitive()) out.add(Map.of("each", p.getAsString(), "eachDistance", "", "eachCount", "1"));
                }
            }
            case "inventory" -> {
                if (s.get("items") instanceof JsonObject items) {
                    for (var e : items.entrySet()) out.add(Map.of("each", e.getKey(), "eachCount", number(e.getValue().getAsDouble()), "eachDistance", ""));
                }
            }
            default -> {}
        }
        return out;
    }

    /** Starts another flow of this bot (it runs as its own owner), and maybe waits for it. */
    private void runFlow(Run run, JsonObject step) throws Exception {
        var targetId = str(step, "flow");
        if (targetId.equals(run.id())) throw new IllegalStateException("A flow can't run itself");
        var target = find(run.instanceId, targetId);
        if (target == null) throw new IllegalStateException("That flow was deleted");
        if (run.depth >= MAX_CALL_DEPTH) throw new IllegalStateException("Flows can run other flows only " + MAX_CALL_DEPTH + " deep");
        var data = event("called");
        data.addProperty("from", run.flow.get("name").getAsString());
        data.addProperty("fromBot", bot.name(run.instanceId));
        data.addProperty("depth", run.depth + 1);
        var key = key(run.instanceId, targetId);
        var before = runsOf(run.instanceId, targetId).peekFirst();
        if (!launch(run.instanceId, target, data, true)) throw new IllegalStateException(target.get("name").getAsString() + " is already running, or too many flows are");
        if (!bool(step, "wait")) return;
        try {
            while (runs.containsKey(key)) Thread.sleep(250);
        } catch (InterruptedException e) {
            stop(run.instanceId, targetId);
            throw e;
        }
        var last = runsOf(run.instanceId, targetId).peekFirst();
        boolean ok = last != null && last != before && bool(last, "ok");
        run.locals.put("result", ok ? "done" : "failed");
        if (!ok && last != null && last != before) run.locals.put("error", str(last, "message"));
    }

    /** A web request through the instance's outbound proxy. {result} done or failed, {code}, {response}. */
    private void webhook(Run run, JsonObject step) throws Exception {
        need(run, Capability.CONTROL);
        var url = render(run, str(step, "url"), 1000, v -> java.net.URLEncoder.encode(v, java.nio.charset.StandardCharsets.UTF_8));
        var uri = java.net.URI.create(url);
        if (uri.getHost() == null || !(uri.getScheme().equalsIgnoreCase("http") || uri.getScheme().equalsIgnoreCase("https"))) {
            throw new IllegalStateException("Not a web address: " + url);
        }
        boolean admin = bot.isAdmin(run.flow.get("ownerId").getAsString());
        for (var address : java.net.InetAddress.getAllByName(uri.getHost())) {
            if (address.isLoopbackAddress() || address.isAnyLocalAddress() || address.isLinkLocalAddress() || address.isMulticastAddress()) {
                throw new SecurityException("Flows can't send web requests to this machine");
            }
            boolean local = address.isSiteLocalAddress() || address instanceof java.net.Inet6Address v6 && (v6.getAddress()[0] & 0xfe) == 0xfc;
            if (local && !admin) throw new SecurityException("Only admins' flows can send web requests into the local network");
        }
        slot(webSent, run.instanceId, WEBHOOK_LIMIT, 60_000);
        var client = java.net.http.HttpClient.newBuilder()
            .connectTimeout(java.time.Duration.ofSeconds(10))
            .followRedirects(java.net.http.HttpClient.Redirect.NEVER);
        var proxy = bot.proxy(run.instanceId);
        if (proxy != null) client.proxy(java.net.ProxySelector.of(proxy));
        var request = java.net.http.HttpRequest.newBuilder(uri).timeout(java.time.Duration.ofSeconds(15)).header("User-Agent", "ZenithHub-Flow");
        if (str(step, "method").equals("POST")) {
            var body = render(run, str(step, "body"), 2000);
            request.header("Content-Type", body.startsWith("{") || body.startsWith("[") ? "application/json" : "text/plain; charset=utf-8");
            request.POST(java.net.http.HttpRequest.BodyPublishers.ofString(body));
        } else {
            request.GET();
        }
        java.net.http.HttpResponse<String> response;
        try (var http = client.build()) {
            response = http.send(request.build(), java.net.http.HttpResponse.BodyHandlers.ofString());
        } catch (java.io.IOException e) {
            run.locals.put("result", "failed");
            throw new IllegalStateException("The web request failed: " + message(e));
        }
        var text = response.body() == null ? "" : response.body().replaceAll("\\s+", " ").strip();
        run.locals.put("code", Integer.toString(response.statusCode()));
        run.locals.put("response", text.length() > 200 ? text.substring(0, 200) : text);
        boolean ok = response.statusCode() >= 200 && response.statusCode() < 300;
        run.locals.put("result", ok ? "done" : "failed");
        log(run, (str(step, "method")) + " " + uri.getHost() + " → " + response.statusCode());
    }

    /** The player's own inventory from the details; a container being open makes slot numbers mean something else. */
    private JsonObject playerInventory(Run run) throws Exception {
        var details = bot.details(run.instanceId);
        if (details == null || !(details.get("inventory") instanceof JsonObject inventory)) throw new IllegalStateException("The bot isn't in the game");
        if (num(inventory, "containerId") != 0) throw new IllegalStateException("A container is open; close it first");
        return inventory;
    }

    private static boolean isItem(JsonElement slot, String item) {
        return slot instanceof JsonObject stack && str(stack, "id").replaceFirst("^minecraft:", "").equals(item);
    }

    /** Takes an item into the main hand: from the hotbar, or swapped in from the inventory. */
    private void holdItem(Run run, String item) throws Exception {
        var inventory = playerInventory(run);
        var slots = inventory.getAsJsonArray("slots");
        int hotbar = (int) num(inventory, "hotbarStart"), held = (int) num(inventory, "heldSlot");
        if (held < slots.size() && isItem(slots.get(held), item)) return;
        for (int i = hotbar; i < hotbar + 9 && i < slots.size(); i++) {
            if (isItem(slots.get(i), item)) {
                checkResult(bot.command(run.instanceId, "inventory hold " + i, run.actor));
                return;
            }
        }
        for (int i = 9; i < hotbar && i < slots.size(); i++) {
            if (isItem(slots.get(i), item)) {
                checkResult(bot.command(run.instanceId, "inventory swap " + i + " " + held, run.actor));
                return;
            }
        }
        throw new IllegalStateException("No " + item.replace('_', ' ') + " in the inventory");
    }

    /** Drops one stack of an item, or all of it. */
    private void dropItem(Run run, String item, boolean all) throws Exception {
        var slots = playerInventory(run).getAsJsonArray("slots");
        int dropped = 0;
        for (int i = 9; i < slots.size(); i++) {
            if (!isItem(slots.get(i), item)) continue;
            checkResult(bot.command(run.instanceId, "inventory drop stack " + i, run.actor));
            dropped++;
            if (!all) break;
            Thread.sleep(150);
        }
        if (dropped == 0) throw new IllegalStateException("No " + item.replace('_', ' ') + " in the inventory");
    }

    /** Turns to look at a position, or at the nearest entity that fits. */
    private void lookAt(Run run, JsonObject step) throws Exception {
        var status = bot.status(run.instanceId);
        if (status == null || !(status.get("position") instanceof JsonObject pos)) throw new IllegalStateException("The bot isn't in the game");
        double tx, ty, tz;
        if (str(step, "target").equals("position")) {
            tx = num(step, "x") + 0.5;
            ty = num(step, "y") + 0.5;
            tz = num(step, "z") + 0.5;
        } else {
            var found = near(step, status.get("entities")).values().stream().findFirst()
                .orElseThrow(() -> new IllegalStateException("Nothing like that within " + number(num(step, "radius")) + " blocks"));
            tx = num(found, "x");
            ty = num(found, "y") + (str(found, "kind").equals("player") ? 1.6 : 0.8);
            tz = num(found, "z");
            run.locals.put("entity", str(found, "name").isEmpty() ? str(found, "type") : str(found, "name"));
        }
        double dx = tx - num(pos, "x"), dy = ty - (num(pos, "y") + 1.62), dz = tz - num(pos, "z");
        double yaw = Math.toDegrees(Math.atan2(-dx, dz));
        double pitch = Math.toDegrees(-Math.atan2(dy, Math.hypot(dx, dz)));
        checkResult(bot.command(run.instanceId, String.format(Locale.ROOT, "rotate %.1f %.1f", yaw, Math.max(-90, Math.min(90, pitch))), run.actor));
    }

    private boolean condition(Run run, JsonObject cond) throws Exception {
        var status = bot.status(run.instanceId);
        var s = status != null ? status : new JsonObject();
        var position = s.get("position") instanceof JsonObject p ? p : new JsonObject();
        var afk = s.get("afkZone") instanceof JsonObject a ? a : new JsonObject();
        var kind = str(cond, "kind");
        if (kind.equals("all") || kind.equals("any")) {
            boolean all = kind.equals("all");
            boolean result = all;
            for (var c : cond.getAsJsonArray("conds")) {
                if (condition(run, c.getAsJsonObject()) != all) {
                    result = !all;
                    break;
                }
            }
            return bool(cond, "not") != result;
        }
        boolean result = switch (kind) {
            case "health" -> s.has("health") && num(s, "health") < cond.get("below").getAsDouble();
            case "food" -> s.has("food") && num(s, "food") < cond.get("below").getAsDouble();
            case "playing" -> bool(s, "controlled");
            case "online" -> bool(s, "online");
            case "inQueue" -> bool(s, "inQueue");
            case "inAfkZone" -> str(afk, "state").equals("IN_ZONE");
            case "earning" -> bool(afk, "earning");
            case "text" -> contains(str(run.data, "message"), str(cond, "contains"));
            case "friend" -> bool(run.data, "friend");
            case "time" -> between(LocalTime.now(java.time.Clock.fixed(Instant.ofEpochMilli(clock.getAsLong()), ZoneId.of(str(cond, "zone")))),
                LocalTime.parse(str(cond, "from")), LocalTime.parse(str(cond, "to")));
            case "chance" -> ThreadLocalRandom.current().nextInt(100) < cond.get("percent").getAsInt();
            case "var" -> compare(scope(run, str(cond, "scope")).getOrDefault(str(cond, "name"), ""), str(cond, "op"), render(run, str(cond, "value"), 100));
            case "near" -> position.has("x") && within(cond, num(position, "x"), num(position, "y"), num(position, "z"));
            case "entityNear" -> !near(cond, s.get("entities")).isEmpty();
            case "pathing" -> s.get("pathing") instanceof JsonObject p && bool(p, "active");
            case "containerOpen" -> s.get("container") instanceof JsonObject;
            case "inventoryFull" -> s.has("freeSlots") && num(s, "freeSlots") == 0;
            case "holding" -> str(s, "held").equals(str(cond, "item"));
            case "block" -> {
                var block = bot.block(run.instanceId, (int) num(cond, "x"), (int) num(cond, "y"), (int) num(cond, "z"));
                yield block != null && bool(block, "loaded") && str(block, "block").equals(str(cond, "is"));
            }
            case "alive" -> bool(s, "alive");
            case "result" -> run.locals.getOrDefault("result", "").equals(str(cond, "is"));
            case "raining" -> bool(s, "raining");
            case "dimension" -> str(position, "dimension").toLowerCase(Locale.ROOT).replace("minecraft:", "").equals(str(cond, "is"));
            case "hasItem" -> countItem(run.instanceId, str(cond, "item")) >= cond.get("count").getAsInt();
            default -> false;
        };
        return bool(cond, "not") != result;
    }

    private int countItem(String instanceId, String item) throws Exception {
        var details = bot.details(instanceId);
        if (details == null || !(details.get("inventory") instanceof JsonObject inventory) || !(inventory.get("slots") instanceof JsonArray slots)) return 0;
        int count = 0;
        for (var slot : slots) {
            if (slot instanceof JsonObject stack && str(stack, "id").replaceFirst("^minecraft:", "").equals(item)) count += (int) num(stack, "count");
        }
        return count;
    }

    static boolean between(LocalTime now, LocalTime from, LocalTime to) {
        // 22:00–06:00 goes over midnight
        return from.isBefore(to) || from.equals(to) ? !now.isBefore(from) && now.isBefore(to) : !now.isBefore(from) || now.isBefore(to);
    }

    static boolean compare(String a, String op, String b) {
        Double x = parse(a), y = parse(b);
        boolean numbers = x != null && y != null;
        return switch (op) {
            case "=" -> numbers ? x.doubleValue() == y.doubleValue() : a.equalsIgnoreCase(b);
            case "!=" -> numbers ? x.doubleValue() != y.doubleValue() : !a.equalsIgnoreCase(b);
            case "<" -> numbers ? x < y : a.compareToIgnoreCase(b) < 0;
            case ">" -> numbers ? x > y : a.compareToIgnoreCase(b) > 0;
            case "contains" -> contains(a, b);
            default -> false;
        };
    }

    private void need(Run run, Capability cap) {
        var owner = run.flow.get("ownerId").getAsString();
        if (!bot.capabilities(run.instanceId, owner).contains(cap)) {
            throw new SecurityException(run.flow.get("ownerName").getAsString() + " may no longer use " + cap.name().toLowerCase(Locale.ROOT) + " here");
        }
    }

    /** Waits until the instance may send another chat message. */
    private void chatSlot(String instanceId) throws InterruptedException {
        slot(chatSent, instanceId, CHAT_LIMIT, CHAT_WINDOW_MS);
    }

    /** Waits until fewer than {@code limit} things were sent in the last {@code window} ms. */
    private void slot(Map<String, Deque<Long>> map, String instanceId, int limit, long window) throws InterruptedException {
        var sent = map.computeIfAbsent(instanceId, k -> new ArrayDeque<>());
        while (true) {
            long wait;
            synchronized (sent) {
                long now = clock.getAsLong();
                while (!sent.isEmpty() && now - sent.peekFirst() >= window) sent.removeFirst();
                if (sent.size() < limit) {
                    sent.addLast(now);
                    return;
                }
                wait = window - (now - sent.peekFirst());
            }
            Thread.sleep(Math.max(50, wait));
        }
    }

    private static void checkResult(JsonObject result) {
        if (result == null || !(result.get("embed") instanceof JsonObject embed)) return;
        if (!bool(embed, "error")) return;
        var text = String.join(": ", List.of(str(embed, "title"), str(embed, "description")).stream().filter(s -> !s.isBlank()).toList());
        throw new IllegalStateException(text.isBlank() ? "The bot refused" : text.replaceAll("\\*\\*|`", ""));
    }

    private void setVar(Run run, String scope, String name, String value) {
        var vars = scope(run, scope);
        if (vars.size() >= MAX_VARIABLES && !vars.containsKey(name)) throw new IllegalStateException("At most " + MAX_VARIABLES + " variables per scope");
        vars.put(name, value);
        if (scope.equals("shared")) persistShared();
    }

    /** A flow's own variables, the ones every flow of this bot shares, or the owner's across all their bots. */
    private Map<String, String> scope(Run run, String scope) {
        return switch (scope) {
            case "bot" -> varsOf(run.instanceId, BOT_VARS);
            case "shared" -> sharedOf(run.flow.get("ownerId").getAsString());
            default -> varsOf(run.instanceId, run.id());
        };
    }

    private void log(Run run, String line) {
        synchronized (run.log) {
            if (run.log.size() < LOG_LINES) run.log.add(line);
        }
        var json = new JsonObject();
        json.addProperty("flowId", run.id());
        json.addProperty("state", "log");
        json.addProperty("message", line);
        events.publish("flow", run.instanceId, json);
    }

    /** Fills in {player}, {message}… from the event, then the flow's variables, then the bot's status. */
    String render(Run run, String template, int max) {
        return render(run, template, max, v -> v);
    }

    /** An action with its {values} filled in; numbers are rounded, so {x} of a player works as a block position. */
    private JsonObject fill(Run run, JsonObject action) {
        var out = new JsonObject();
        for (var e : action.entrySet()) {
            var v = e.getValue();
            if (v instanceof JsonObject o) {
                out.add(e.getKey(), fill(run, o));
            } else if (FlowSpec.hasValue(e.getKey(), v)) {
                var text = render(run, v.getAsString(), 100);
                if (FlowSpec.NUMERIC.contains(e.getKey())) {
                    try {
                        double d = Double.parseDouble(text);
                        out.addProperty(e.getKey(), e.getKey().equals("yaw") || e.getKey().equals("pitch") ? d : Math.floor(d));
                        continue;
                    } catch (NumberFormatException ex) {
                        throw new IllegalArgumentException(v.getAsString() + " isn't a number here: “" + text + "”");
                    }
                }
                out.addProperty(e.getKey(), text);
            } else {
                out.add(e.getKey(), v);
            }
        }
        return out;
    }

    /** {@code encode} escapes each value, for web addresses. */
    String render(Run run, String template, int max, java.util.function.UnaryOperator<String> encode) {
        var m = PLACEHOLDER.matcher(template);
        var out = new StringBuilder();
        while (m.find()) {
            var value = value(run, m.group(1));
            value = value.replaceAll("[\\r\\n\\t]+", " ");
            if (value.length() > 200) value = value.substring(0, 200);
            m.appendReplacement(out, Matcher.quoteReplacement(encode.apply(value)));
        }
        m.appendTail(out);
        var text = out.toString().strip();
        return text.length() > max ? text.substring(0, max) : text;
    }

    /** The event's details, then what blocks left ({result}…), the variables (flow, bot, shared), then the bot's status. */
    private String value(Run run, String name) {
        var data = run.data;
        if (name.equals("player")) {
            var p = first(data, "player", "from", "attacker");
            if (!p.isEmpty()) return p;
        } else if (!str(data, name).isEmpty()) {
            var e = data.get(name);
            return e.getAsJsonPrimitive().isNumber() ? number(e.getAsDouble()) : e.getAsString();
        }
        if (run.locals.containsKey(name)) return run.locals.get(name);
        for (var scope : List.of("flow", "bot", "shared")) {
            var vars = scope(run, scope);
            if (vars.containsKey(name)) return vars.get(name);
        }
        var status = bot.status(run.instanceId);
        var s = status != null ? status : new JsonObject();
        var position = s.get("position") instanceof JsonObject p ? p : new JsonObject();
        var now = Instant.ofEpochMilli(clock.getAsLong()).atZone(ZoneId.systemDefault());
        if (name.startsWith("count:")) return number(itemCount(s.get("items"), name.substring(6)));
        JsonObject nearest = null;
        if (s.get("entities") instanceof JsonArray entities) {
            for (var e : entities) if (e instanceof JsonObject o && str(o, "kind").equals("player") && !str(o, "name").isEmpty()) {
                nearest = o;
                break;
            }
        }
        return switch (name) {
            case "health", "food" -> s.has(name) ? number(num(s, name)) : "";
            case "x", "y", "z" -> position.has(name) ? number(Math.floor(num(position, name))) : "";
            case "yaw", "pitch" -> position.has(name) ? number(Math.round(num(position, name))) : "";
            case "ping", "freeSlots" -> s.has(name) ? number(num(s, name)) : "";
            case "tps" -> s.has("tps") ? number(Math.round(num(s, "tps") * 10) / 10.0) : "";
            case "held" -> str(s, "held");
            case "nearestPlayer" -> nearest != null ? str(nearest, "name") : "";
            case "nearestDistance" -> nearest != null ? number(Math.round(num(nearest, "distance"))) : "";
            case "dimension" -> str(position, "dimension").replace("minecraft:", "");
            case "server" -> bot.server(run.instanceId);
            case "time" -> now.format(DateTimeFormatter.ofPattern("HH:mm"));
            case "date" -> now.format(DateTimeFormatter.ISO_LOCAL_DATE);
            default -> "";
        };
    }

    private void publish(Run run, String state, @Nullable String error) {
        var json = new JsonObject();
        json.addProperty("flowId", run.id());
        json.addProperty("state", state);
        if (run.step != null) json.addProperty("step", run.step);
        if (error != null) json.addProperty("message", error);
        events.publish("flow", run.instanceId, json);
    }

    private void changed(String instanceId) {
        var json = new JsonObject();
        json.addProperty("state", "changed");
        events.publish("flow", instanceId, json);
    }

    private static JsonObject summary(JsonObject run) {
        var json = new JsonObject();
        for (var k : List.of("ok", "message", "step", "startedAt", "at")) if (run.has(k)) json.add(k, run.get(k));
        return json;
    }

    /** What started a run, for the run list. */
    static String describe(JsonObject data) {
        var event = str(data, "event");
        var player = first(data, "player", "from", "attacker");
        var message = str(data, "message");
        var parts = new ArrayList<String>();
        parts.add(switch (event) {
            case "manual" -> "Run button";
            case "test" -> "test run";
            case "called" -> "run by " + str(data, "from");
            case "entityNear" -> "came within " + number(num(data, "distance")) + " blocks";
            case "entityLeft" -> "went away";
            case "serverJoin" -> "joined the server";
            case "serverLeave" -> "left the server";
            case "flowFailed" -> str(data, "flow") + " failed";
            case "blockChanged" -> str(data, "previous") + " → " + str(data, "block");
            case "itemBelow", "itemAbove" -> str(data, "item").replace('_', ' ') + ": " + number(num(data, "count"));
            case "durability" -> str(data, "item").replace('_', ' ') + " at " + number(num(data, "percent")) + "%";
            case "dimension" -> "went to " + str(data, "dimension").replace('_', ' ');
            case "left" -> "left the game" + (str(data, "why").isEmpty() ? "" : " (" + str(data, "why") + ")");
            case "visualRange" -> str(data, "action").equals("enter") ? "came into view" : "left view";
            case "receive" -> "broadcast " + str(data, "name");
            case "afk" -> "AFK zone: " + str(data, "on");
            case "controlled" -> str(data, "on").equals("start") ? "someone started playing" : "nobody is playing";
            case "queue" -> "queue position " + number(num(data, "position"));
            case "onlineTime" -> "online for " + number(Math.floor(num(data, "seconds") / 60)) + " min";
            case "unattended" -> "nobody played for " + number(Math.floor(num(data, "seconds") / 60)) + " min";
            case "moved" -> "arrived at " + number(Math.floor(num(data, "x"))) + ", " + number(Math.floor(num(data, "z")));
            case "proxyJoin" -> "joined the proxy";
            case "proxyLeave" -> "left the proxy";
            case "" -> "trigger";
            default -> event;
        });
        if (!player.isEmpty()) parts.add(player);
        if (!message.isEmpty()) parts.add("“" + (message.length() > 60 ? message.substring(0, 60) + "…" : message) + "”");
        return String.join(" · ", parts);
    }

    // --- storage ---

    private List<JsonObject> load(String instanceId) {
        return flows.computeIfAbsent(instanceId, id -> {
            var list = new ArrayList<JsonObject>();
            var file = file(id);
            try {
                if (Files.exists(file)) {
                    for (var e : JsonParser.parseString(Files.readString(file)).getAsJsonArray()) list.add(e.getAsJsonObject());
                }
            } catch (Exception e) {
                LOG.warn("Unreadable flows {}", file, e);
            }
            loadState(id);
            for (var flow : list) schedule(id, flow);
            return List.copyOf(list);
        });
    }

    private void loadState(String instanceId) {
        var file = stateFile(instanceId);
        var h = new ConcurrentHashMap<String, Deque<JsonObject>>();
        var v = new ConcurrentHashMap<String, Map<String, String>>();
        try {
            if (Files.exists(file)) {
                var json = JsonParser.parseString(Files.readString(file)).getAsJsonObject();
                if (json.get("runs") instanceof JsonObject r) {
                    for (var e : r.entrySet()) {
                        var deque = new ArrayDeque<JsonObject>();
                        e.getValue().getAsJsonArray().forEach(x -> deque.addLast(x.getAsJsonObject()));
                        h.put(e.getKey(), deque);
                    }
                }
                if (json.get("variables") instanceof JsonObject vars) {
                    for (var e : vars.entrySet()) {
                        var map = new ConcurrentHashMap<String, String>();
                        e.getValue().getAsJsonObject().entrySet().forEach(x -> map.put(x.getKey(), x.getValue().getAsString()));
                        v.put(e.getKey(), map);
                    }
                }
            }
        } catch (Exception e) {
            LOG.warn("Unreadable flow state {}", file, e);
        }
        history.put(instanceId, h);
        variables.put(instanceId, v);
    }

    private void persist(String instanceId, List<JsonObject> list) {
        var array = new JsonArray();
        list.forEach(array::add);
        write(file(instanceId), Json.GSON.toJson(array));
        flows.put(instanceId, List.copyOf(list));
    }

    private void persistState(String instanceId) {
        var json = new JsonObject();
        var r = new JsonObject();
        historyOf(instanceId).forEach((id, list) -> {
            var array = new JsonArray();
            list.forEach(array::add);
            r.add(id, array);
        });
        json.add("runs", r);
        var v = new JsonObject();
        variablesOf(instanceId).forEach((id, map) -> {
            var o = new JsonObject();
            new LinkedHashMap<>(map).forEach(o::addProperty);
            v.add(id, o);
        });
        json.add("variables", v);
        try {
            write(stateFile(instanceId), Json.COMPACT.toJson(json));
        } catch (Exception e) {
            LOG.warn("Couldn't save flow state of {}", instanceId, e);
        }
    }

    private void write(Path target, String content) {
        try {
            Files.createDirectories(dir);
            var tmp = target.resolveSibling(target.getFileName() + ".tmp");
            Files.writeString(tmp, content);
            Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException e) {
            throw new IllegalStateException("Couldn't save the flows", e);
        }
    }

    private Map<String, Deque<JsonObject>> historyOf(String instanceId) {
        load(instanceId);
        return history.computeIfAbsent(instanceId, k -> new ConcurrentHashMap<>());
    }

    private Deque<JsonObject> runsOf(String instanceId, String flowId) {
        return historyOf(instanceId).computeIfAbsent(flowId, k -> new ArrayDeque<>());
    }

    private Map<String, Map<String, String>> variablesOf(String instanceId) {
        load(instanceId);
        return variables.computeIfAbsent(instanceId, k -> new ConcurrentHashMap<>());
    }

    private Map<String, String> varsOf(String instanceId, String flowId) {
        return variablesOf(instanceId).computeIfAbsent(flowId, k -> new ConcurrentHashMap<>());
    }

    private Map<String, String> sharedOf(String ownerId) {
        loadShared();
        return shared.computeIfAbsent(ownerId, k -> new ConcurrentHashMap<>());
    }

    private synchronized void loadShared() {
        if (sharedLoaded) return;
        var file = dir.resolve("shared.vars.json");
        try {
            if (Files.exists(file)) {
                for (var e : JsonParser.parseString(Files.readString(file)).getAsJsonObject().entrySet()) {
                    var map = new ConcurrentHashMap<String, String>();
                    e.getValue().getAsJsonObject().entrySet().forEach(x -> map.put(x.getKey(), x.getValue().getAsString()));
                    shared.put(e.getKey(), map);
                }
            }
        } catch (Exception e) {
            LOG.warn("Unreadable shared flow variables {}", file, e);
        }
        sharedLoaded = true;
    }

    private synchronized void persistShared() {
        var json = new JsonObject();
        shared.forEach((owner, map) -> {
            var o = new JsonObject();
            new LinkedHashMap<>(map).forEach(o::addProperty);
            json.add(owner, o);
        });
        try {
            write(dir.resolve("shared.vars.json"), Json.COMPACT.toJson(json));
        } catch (Exception e) {
            LOG.warn("Couldn't save shared flow variables", e);
        }
    }

    private Path file(String instanceId) {
        if (!FlowSpec.ID.matcher(instanceId).matches()) throw new IllegalArgumentException("Invalid instance");
        return dir.resolve(instanceId + ".json");
    }

    private Path stateFile(String instanceId) {
        if (!FlowSpec.ID.matcher(instanceId).matches()) throw new IllegalArgumentException("Invalid instance");
        return dir.resolve(instanceId + ".state.json");
    }

    private @Nullable JsonObject find(String instanceId, String flowId) {
        var list = load(instanceId);
        int at = indexOf(list, flowId);
        return at >= 0 ? list.get(at) : null;
    }

    private static int indexOf(List<JsonObject> list, String id) {
        for (int i = 0; i < list.size(); i++) if (list.get(i).get("id").getAsString().equals(id)) return i;
        return -1;
    }

    private static String key(String instanceId, String flowId) {
        return instanceId + "/" + flowId;
    }

    // --- json ---

    private static JsonObject event(String kind) {
        var json = new JsonObject();
        json.addProperty("event", kind);
        return json;
    }

    private static boolean contains(String text, String part) {
        return part.isEmpty() || text.toLowerCase(Locale.ROOT).contains(part.toLowerCase(Locale.ROOT));
    }

    private static @Nullable Double parse(String s) {
        try {
            return Double.parseDouble(s.strip());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    static String number(double d) {
        return d == Math.rint(d) && Math.abs(d) < 1e15 ? Long.toString((long) d) : Double.toString(Math.round(d * 100) / 100.0);
    }

    private static String first(JsonObject data, String... keys) {
        for (var k : keys) if (!str(data, k).isEmpty()) return str(data, k);
        return "";
    }

    static String str(JsonObject json, String key) {
        JsonElement e = json.get(key);
        return e == null || e.isJsonNull() || !e.isJsonPrimitive() ? "" : e.getAsString();
    }

    static boolean bool(JsonObject json, String key) {
        JsonElement e = json.get(key);
        return e != null && e.isJsonPrimitive() && e.getAsJsonPrimitive().isBoolean() && e.getAsBoolean();
    }

    private static double num(JsonObject json, String key) {
        JsonElement e = json.get(key);
        return e != null && e.isJsonPrimitive() && e.getAsJsonPrimitive().isNumber() ? e.getAsDouble() : Double.NaN;
    }
}
