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
    private static final Pattern PLACEHOLDER = Pattern.compile("\\{([a-zA-Z][a-zA-Z0-9_]*)}");

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
        int executed;
        /** the trigger's details, then each "wait until"'s, for {player}, {message}… */
        JsonObject data;

        Run(String instanceId, JsonObject flow, JsonObject data, long now) {
            this.instanceId = instanceId;
            this.flow = flow;
            this.data = data;
            this.startedAt = now;
            this.actor = "flow:" + flow.get("name").getAsString();
            this.cause = describe(data);
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
            files.map(p -> p.getFileName().toString()).filter(n -> n.endsWith(".json") && !n.endsWith(".state.json"))
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
        var flow = find(instanceId, flowId);
        if (flow == null) throw new IllegalArgumentException("No such flow");
        var data = new JsonObject();
        data.addProperty("event", "manual");
        if (!launch(instanceId, flow, data, true)) throw new IllegalStateException("It's already running, or too many flows are running");
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
            dispatch(instanceId, event.payload());
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
            now.addProperty("z", num(pos.getAsJsonObject(), "z"));
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
            if (!bool(now, "online")) e.addProperty("message", str(status, "lastDisconnectReason"));
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
        if (now.has("x") && before.has("x") && (num(now, "x") != num(before, "x") || num(now, "z") != num(before, "z"))) {
            var e = event("moved");
            e.addProperty("x", num(now, "x"));
            e.addProperty("z", num(now, "z"));
            e.addProperty("previousX", num(before, "x"));
            e.addProperty("previousZ", num(before, "z"));
            out.add(e);
        }
        var had = new java.util.HashSet<String>();
        if (before.has("players")) for (var p : before.getAsJsonArray("players")) had.add(p.getAsString());
        for (var p : players) if (!had.contains(p)) out.add(proxyEvent("proxyJoin", p));
        for (var p : had) if (!players.contains(p)) out.add(proxyEvent("proxyLeave", p));
        return out;
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
            if (w.instanceId.equals(instanceId) && matches(w.trigger, data)) w.future.complete(data);
        }
        for (var flow : load(instanceId)) {
            if (flow.get("enabled").getAsBoolean() && matches(flow.getAsJsonObject("trigger"), data)) launch(instanceId, flow, data, false);
        }
    }

    void tick(long now) {
        for (var entry : flows.entrySet()) {
            var instanceId = entry.getKey();
            var status = bot.status(instanceId);
            boolean online = status != null && bool(status, "online");
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
                && contains(str(data, "message"), str(trigger, "contains"))
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
            case "left" -> event.equals("left") && contains(str(data, "message"), str(trigger, "reason"));
            case "controlled", "afk" -> event.equals(kind) && str(data, "on").equals(str(trigger, "on"));
            case "receive" -> event.equals("receive") && str(data, "name").equalsIgnoreCase(str(trigger, "name"));
            // once per session or stretch: when the time passes the mark
            case "onlineFor" -> event.equals("onlineTime") && crossed(data, trigger.get("minutes").getAsDouble() * 60);
            case "unattended" -> event.equals("unattended") && crossed(data, trigger.get("minutes").getAsDouble() * 60);
            case "arrived" -> event.equals("moved")
                && within(trigger, num(data, "x"), num(data, "z")) && !within(trigger, num(data, "previousX"), num(data, "previousZ"));
            case "proxyJoin", "proxyLeave" -> event.equals(kind)
                && (str(trigger, "who").equals("anyone") || str(trigger, "who").isEmpty() || str(trigger, "who").equalsIgnoreCase(str(data, "player")));
            case "manual", "every", "daily" -> false;
            default -> event.equals(kind);
        };
    }

    private static boolean crossed(JsonObject data, double mark) {
        return num(data, "seconds") >= mark && num(data, "previous") < mark;
    }

    private static boolean within(JsonObject trigger, double x, double z) {
        return Math.hypot(x - num(trigger, "x"), z - num(trigger, "z")) <= num(trigger, "radius");
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
        synchronized (this) {
            var list = runsOf(run.instanceId, run.id());
            list.addFirst(result);
            while (list.size() > KEEP_RUNS) list.removeLast();
            persistState(run.instanceId);
            runs.remove(key, run);
        }
        publish(run, error == null ? "done" : "error", error);
    }

    private void steps(Run run, JsonArray steps) throws Exception {
        for (var element : steps) {
            if (Thread.interrupted()) throw new InterruptedException();
            var step = element.getAsJsonObject();
            if (bool(step, "off")) continue;
            if (++run.executed > MAX_EXECUTED) throw new IllegalStateException("Ran more than " + MAX_EXECUTED + " blocks, stopped. Add a wait to loops.");
            run.step = step.get("id").getAsString();
            publish(run, "step", null);
            step(run, step);
        }
    }

    private void step(Run run, JsonObject step) throws Exception {
        var id = run.instanceId;
        switch (step.get("type").getAsString()) {
            case "action" -> {
                var action = Actions.parse(step.getAsJsonObject("run"));
                need(run, action.capability());
                if (action.command().startsWith("pearlLoader load ")) {
                    bot.loadPearl(id, action.command().substring("pearlLoader load ".length()), run.actor);
                } else {
                    checkResult(bot.command(id, action.command(), run.actor));
                }
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
                bot.connect(id, run.actor);
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
                } catch (TimeoutException e) {
                    log(run, "Stopped waiting after " + step.get("timeout").getAsLong() + " s");
                } finally {
                    waiters.remove(waiter);
                }
            }
            case "waitUntil" -> {
                long timeout = step.get("timeout").getAsLong() * 1000;
                long until = clock.getAsLong() + timeout;
                while (!condition(run, step.getAsJsonObject("cond"))) {
                    if (timeout > 0 && clock.getAsLong() >= until) {
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
            case "set" -> setVar(run, step.get("name").getAsString(), render(run, step.get("value").getAsString(), 200));
            case "change" -> {
                var name = step.get("name").getAsString();
                double current;
                try {
                    current = Double.parseDouble(varsOf(id, run.id()).getOrDefault(name, "0"));
                } catch (NumberFormatException e) {
                    current = 0;
                }
                setVar(run, name, number(current + step.get("by").getAsDouble()));
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

    private boolean condition(Run run, JsonObject cond) throws Exception {
        var status = bot.status(run.instanceId);
        var s = status != null ? status : new JsonObject();
        var position = s.get("position") instanceof JsonObject p ? p : new JsonObject();
        var afk = s.get("afkZone") instanceof JsonObject a ? a : new JsonObject();
        boolean result = switch (str(cond, "kind")) {
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
            case "var" -> compare(varsOf(run.instanceId, run.id()).getOrDefault(str(cond, "name"), ""), str(cond, "op"), render(run, str(cond, "value"), 100));
            case "near" -> position.has("x") && Math.hypot(num(position, "x") - num(cond, "x"), num(position, "z") - num(cond, "z")) <= num(cond, "radius");
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
        var sent = chatSent.computeIfAbsent(instanceId, k -> new ArrayDeque<>());
        while (true) {
            long wait;
            synchronized (sent) {
                long now = clock.getAsLong();
                while (!sent.isEmpty() && now - sent.peekFirst() >= CHAT_WINDOW_MS) sent.removeFirst();
                if (sent.size() < CHAT_LIMIT) {
                    sent.addLast(now);
                    return;
                }
                wait = CHAT_WINDOW_MS - (now - sent.peekFirst());
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

    private void setVar(Run run, String name, String value) {
        varsOf(run.instanceId, run.id()).put(name, value);
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
        var m = PLACEHOLDER.matcher(template);
        var out = new StringBuilder();
        var vars = varsOf(run.instanceId, run.id());
        while (m.find()) {
            var value = value(run, m.group(1), vars);
            value = value.replaceAll("[\\r\\n\\t]+", " ");
            if (value.length() > 100) value = value.substring(0, 100);
            m.appendReplacement(out, Matcher.quoteReplacement(value));
        }
        m.appendTail(out);
        var text = out.toString().strip();
        return text.length() > max ? text.substring(0, max) : text;
    }

    private String value(Run run, String name, Map<String, String> vars) {
        var data = run.data;
        if (name.equals("player")) {
            var p = first(data, "player", "from", "attacker");
            if (!p.isEmpty()) return p;
        } else if (!str(data, name).isEmpty()) {
            var e = data.get(name);
            return e.getAsJsonPrimitive().isNumber() ? number(e.getAsDouble()) : e.getAsString();
        }
        if (vars.containsKey(name)) return vars.get(name);
        var status = bot.status(run.instanceId);
        var s = status != null ? status : new JsonObject();
        var position = s.get("position") instanceof JsonObject p ? p : new JsonObject();
        var now = Instant.ofEpochMilli(clock.getAsLong()).atZone(ZoneId.systemDefault());
        return switch (name) {
            case "health", "food" -> s.has(name) ? number(num(s, name)) : "";
            case "x", "y", "z" -> position.has(name) ? number(Math.floor(num(position, name))) : "";
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
