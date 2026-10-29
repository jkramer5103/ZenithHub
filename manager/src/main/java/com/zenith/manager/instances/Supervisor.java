package com.zenith.manager.instances;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.zenith.manager.EventHub;
import com.zenith.manager.ManagerConfig;
import com.zenith.manager.accounts.AccountService;
import com.zenith.manager.auth.Passwords;
import com.zenith.manager.state.State;
import com.zenith.manager.state.StateStore;
import com.zenith.manager.util.Json;
import com.zenith.manager.util.SecureFiles;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;

/**
 * Runs one Zenith process per instance: start, stop, crash restarts with backoff,
 * status polling, log streaming, and restoring the previous state when the manager starts.
 */
public final class Supervisor {
    private static final Logger LOG = LoggerFactory.getLogger("Supervisor");
    private static final Pattern LOG_LINE = Pattern.compile("^\\[[^\\]]*] \\[([^\\]]*)] \\[([A-Z]+)] (.*)$");
    private static final Pattern LOG_PREFIX = Pattern.compile("^\\[[^\\]]*] \\[[^\\]]*] \\[[A-Z]+] ");
    private static final Pattern ANSI = Pattern.compile("\\e\\[[\\d;]*[^\\d;]");
    /** Zenith exits 69 when it should stay stopped (shutdown command, invalid config). */
    private static final int EXIT_STAY_STOPPED = 69;
    /** Zenith exits 0 when it asks to be restarted (restart command). */
    private static final int EXIT_RESTART = 0;
    private static final long STABLE_RUN_MS = Duration.ofMinutes(10).toMillis();

    private final ManagerConfig config;
    private final StateStore store;
    private final AccountService accounts;
    private final EventHub events;
    private final Map<String, InstanceRuntime> runtimes = new ConcurrentHashMap<>();
    private final ScheduledExecutorService scheduler = Executors.newScheduledThreadPool(2, Thread.ofPlatform().name("supervisor-", 0).daemon().factory());
    private final HttpClient http = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(2))
        .executor(Executors.newVirtualThreadPerTaskExecutor())
        .build();
    private volatile boolean shuttingDown;

    public Supervisor(ManagerConfig config, StateStore store, AccountService accounts, EventHub events) {
        this.config = config;
        this.store = store;
        this.accounts = accounts;
        this.events = events;
    }

    public void startBackgroundTasks() {
        scheduler.scheduleWithFixedDelay(this::pollAll, 2, 2, TimeUnit.SECONDS);
    }

    public InstanceRuntime runtime(String instanceId) {
        return runtimes.computeIfAbsent(instanceId, InstanceRuntime::new);
    }

    /** Starts instances that should be running: autostart ALWAYS, or RESTORE and running when the manager stopped. */
    public void restorePreviousState() {
        var toStart = store.read(s -> s.instances.stream()
            .filter(i -> i.autostart == State.Autostart.ALWAYS || (i.autostart == State.Autostart.RESTORE && i.desiredRunning))
            .map(i -> i.id)
            .toList());
        for (var id : toStart) {
            LOG.info("Restoring instance {}", id);
            start(id);
        }
    }

    public void start(String instanceId) {
        var rt = runtime(instanceId);
        synchronized (rt) {
            if (rt.process != null && rt.process.isAlive()) return;
            setDesiredRunning(instanceId, true);
            rt.stopRequested = false;
            rt.lastError = null;
            try {
                spawn(rt);
            } catch (Exception e) {
                LOG.error("Failed starting instance {}", instanceId, e);
                rt.lastError = "Failed to start: " + e.getMessage();
                rt.phase = InstanceRuntime.Phase.STOPPED;
                setDesiredRunning(instanceId, false);
            }
        }
        publishRuntime(rt);
    }

    public void stop(String instanceId) {
        setDesiredRunning(instanceId, false);
        stopProcess(runtime(instanceId));
    }

    public void restart(String instanceId) {
        var rt = runtime(instanceId);
        stopProcess(rt);
        start(instanceId);
    }

    /**
     * What users mean by "connect": start the instance if it isn't running, then join the server.
     * A fresh process connects by itself because client.autoConnect is forced from lastConnected.
     */
    public void connect(String instanceId, String actor) throws IOException, InterruptedException {
        setLastConnected(instanceId, true);
        var rt = runtime(instanceId);
        boolean alive = rt.process != null && rt.process.isAlive();
        start(instanceId);
        if (!alive) return;
        if (!awaitRunning(instanceId, Duration.ofSeconds(60))) throw new IllegalStateException("The instance didn't start");
        var status = rt.status;
        if (status == null || !status.get("connected").getAsBoolean()) command(instanceId, "connect", actor);
    }

    /** Leaves the server and shuts the instance down, unless active hours needs it to reconnect later. */
    public void disconnect(String instanceId, String actor) throws IOException, InterruptedException {
        setLastConnected(instanceId, false);
        if (!isRunning(instanceId)) {
            stop(instanceId);
            return;
        }
        command(instanceId, "disconnect", actor);
        if (!activeHoursEnabled(instanceId)) stop(instanceId);
    }

    /**
     * Waits for a connect to settle. Returns null once connected, the disconnect reason if it failed,
     * or "still connecting" if neither happened in time.
     */
    public String awaitConnected(String instanceId, long since, Duration timeout) throws InterruptedException {
        var rt = runtime(instanceId);
        long deadline = System.currentTimeMillis() + timeout.toMillis();
        while (System.currentTimeMillis() < deadline) {
            if (rt.phase == InstanceRuntime.Phase.STOPPED || rt.phase == InstanceRuntime.Phase.BACKOFF)
                return rt.lastError != null ? rt.lastError : "The instance stopped";
            var status = rt.status;
            if (rt.phase == InstanceRuntime.Phase.RUNNING && status != null) {
                if (status.get("connected").getAsBoolean()) return null;
                if (status.has("lastDisconnectAt") && status.get("lastDisconnectAt").getAsLong() >= since)
                    return status.get("lastDisconnectReason").getAsString();
            }
            Thread.sleep(500);
        }
        return "still connecting";
    }

    private boolean awaitRunning(String instanceId, Duration timeout) throws InterruptedException {
        long deadline = System.currentTimeMillis() + timeout.toMillis();
        while (System.currentTimeMillis() < deadline) {
            var phase = runtime(instanceId).phase;
            if (phase == InstanceRuntime.Phase.RUNNING) return true;
            if (phase != InstanceRuntime.Phase.STARTING) return false;
            Thread.sleep(250);
        }
        return false;
    }

    private boolean activeHoursEnabled(String instanceId) {
        try {
            var activeHours = liveConfig(instanceId).getAsJsonObject("client").getAsJsonObject("extra").getAsJsonObject("utility")
                .getAsJsonObject("actions").getAsJsonObject("activeHours");
            return activeHours.get("enabled").getAsBoolean();
        } catch (Exception e) {
            return false;
        }
    }

    /** Stops every process without touching desiredRunning, so they come back on the next start. */
    public void shutdownAll() {
        shuttingDown = true;
        runtimes.values().parallelStream().forEach(this::stopProcess);
        scheduler.shutdownNow();
    }

    public void forget(String instanceId) {
        stop(instanceId);
        runtimes.remove(instanceId);
    }

    public JsonObject command(String instanceId, String command, String actor) throws IOException, InterruptedException {
        var rt = runtime(instanceId);
        if (rt.phase != InstanceRuntime.Phase.RUNNING) throw new IllegalStateException("Instance is not running");
        var body = new JsonObject();
        body.addProperty("command", command);
        body.addProperty("actor", actor);
        var response = http.send(request(rt, "/v1/command")
                .timeout(Duration.ofSeconds(30))
                .POST(HttpRequest.BodyPublishers.ofString(Json.COMPACT.toJson(body)))
                .header("Content-Type", "application/json")
                .build(),
            HttpResponse.BodyHandlers.ofString());
        var json = JsonParser.parseString(response.body()).getAsJsonObject();
        if (response.statusCode() != 200) {
            throw new IllegalStateException(json.has("error") ? json.get("error").getAsString() : "Command failed");
        }
        return json;
    }

    public boolean isRunning(String instanceId) {
        var rt = runtimes.get(instanceId);
        return rt != null && rt.phase == InstanceRuntime.Phase.RUNNING;
    }

    public boolean isConnected(String instanceId) {
        var rt = runtimes.get(instanceId);
        var status = rt != null && rt.phase == InstanceRuntime.Phase.RUNNING ? rt.status : null;
        return status != null && status.get("connected").getAsBoolean();
    }

    /** The config the running instance is using right now, including changes made in-game. */
    public JsonObject liveConfig(String instanceId) throws IOException, InterruptedException {
        var response = http.send(request(runtime(instanceId), "/v1/config").timeout(Duration.ofSeconds(10)).GET().build(),
            HttpResponse.BodyHandlers.ofString());
        return controlResponse(response);
    }

    /** Changes settings of a running instance without restarting it. */
    public void pushConfig(String instanceId, JsonObject settings) throws IOException, InterruptedException {
        var body = new JsonObject();
        body.add("settings", settings);
        var response = http.send(request(runtime(instanceId), "/v1/config")
                .timeout(Duration.ofSeconds(10))
                .POST(HttpRequest.BodyPublishers.ofString(Json.COMPACT.toJson(body)))
                .header("Content-Type", "application/json")
                .build(),
            HttpResponse.BodyHandlers.ofString());
        controlResponse(response);
    }

    private static JsonObject controlResponse(HttpResponse<String> response) {
        var json = JsonParser.parseString(response.body()).getAsJsonObject();
        if (response.statusCode() != 200) {
            throw new IllegalStateException(json.has("error") ? json.get("error").getAsString() : "Instance request failed");
        }
        return json;
    }

    // --- process lifecycle ---

    private void spawn(InstanceRuntime rt) throws IOException {
        var ctx = context(rt.instanceId);
        var dir = config.instanceDir(rt.instanceId);
        Files.createDirectories(dir);
        SecureFiles.writeString(dir.resolve("config.json"), Json.GSON.toJson(ConfigComposer.compose(zenithDefaults(dir), ctx)));
        accounts.syncAuthCache(ctx.account(), dir);

        rt.token = Passwords.randomToken(32);
        var pb = new ProcessBuilder(config.zenithCommand())
            .directory(dir.toFile())
            .redirectErrorStream(true);
        var env = pb.environment();
        env.put("ZENITH_MANAGED", "1");
        env.put("ZENITH_CONTROL_PORT", Integer.toString(ctx.instance().controlPort));
        env.put("ZENITH_CONTROL_TOKEN", rt.token);
        env.remove("ZENITH_LAUNCHER");
        if (config.cheatsDisabled()) env.put("ZENITH_DISABLE_CHEATS", "1");
        else env.remove("ZENITH_DISABLE_CHEATS");

        rt.status = null;
        rt.eventsConnected = false;
        rt.phase = InstanceRuntime.Phase.STARTING;
        rt.startedAt = System.currentTimeMillis();
        var process = pb.start();
        rt.process = process;
        var exitHandled = new java.util.concurrent.CompletableFuture<Void>();
        rt.exitHandled = exitHandled;
        LOG.info("Started instance {} (pid {})", rt.instanceId, process.pid());
        appendLog(rt, "Manager", "INFO", "Started process " + process.pid(), null);

        Thread.ofVirtual().name("stdout-" + rt.instanceId).start(() -> readStdout(rt, process));
        Thread.ofVirtual().name("events-" + rt.instanceId).start(() -> streamEvents(rt, process));
        process.onExit().thenAccept(p -> {
            try {
                onExit(rt, p);
            } finally {
                exitHandled.complete(null);
            }
        });
    }

    private void stopProcess(InstanceRuntime rt) {
        Process process;
        java.util.concurrent.CompletableFuture<Void> exitHandled;
        synchronized (rt) {
            process = rt.process;
            exitHandled = rt.exitHandled;
            if (process == null || !process.isAlive()) {
                if (rt.phase == InstanceRuntime.Phase.BACKOFF) {
                    rt.phase = InstanceRuntime.Phase.STOPPED;
                    publishRuntime(rt);
                }
                return;
            }
            rt.stopRequested = true;
            rt.phase = InstanceRuntime.Phase.STOPPING;
        }
        publishRuntime(rt);
        try {
            http.send(request(rt, "/v1/shutdown").timeout(Duration.ofSeconds(3)).POST(HttpRequest.BodyPublishers.noBody()).build(),
                HttpResponse.BodyHandlers.discarding());
        } catch (Exception e) {
            process.destroy();
        }
        try {
            if (!process.waitFor(20, TimeUnit.SECONDS)) {
                process.destroy();
                if (!process.waitFor(10, TimeUnit.SECONDS)) process.destroyForcibly();
            }
        } catch (Exception e) {
            process.destroyForcibly();
        }
        try {
            exitHandled.get(15, TimeUnit.SECONDS);
        } catch (Exception e) {
            LOG.warn("Timed out waiting for instance {} exit handling", rt.instanceId);
        }
    }

    private void onExit(InstanceRuntime rt, Process process) {
        int code = process.exitValue();
        long ranMs = System.currentTimeMillis() - rt.startedAt;
        LOG.info("Instance {} exited with code {} after {}s", rt.instanceId, code, ranMs / 1000);
        appendLog(rt, "Manager", code == 0 || rt.stopRequested ? "INFO" : "WARN", "Process exited with code " + code, null);
        persistInstanceSettings(rt.instanceId);
        synchronized (rt) {
            if (rt.process != process) return; // a newer process already replaced this one
            rt.process = null;
            rt.status = null;
            rt.eventsConnected = false;
            if (rt.stopRequested || shuttingDown) {
                rt.phase = InstanceRuntime.Phase.STOPPED;
            } else if (code == EXIT_STAY_STOPPED) {
                rt.phase = InstanceRuntime.Phase.STOPPED;
                rt.lastError = "Instance shut itself down (shutdown command or invalid config). Check the console.";
                setDesiredRunning(rt.instanceId, false);
            } else if (code == EXIT_RESTART) {
                rt.crashCount = 0;
                scheduleRestart(rt, 1_000);
            } else {
                rt.crashCount = ranMs > STABLE_RUN_MS ? 1 : rt.crashCount + 1;
                long delay = Math.min(300_000L, 5_000L << Math.min(6, rt.crashCount - 1));
                rt.lastError = "Crashed with exit code " + code + ", restarting in " + delay / 1000 + "s";
                scheduleRestart(rt, delay);
            }
        }
        publishRuntime(rt);
    }

    private void scheduleRestart(InstanceRuntime rt, long delayMs) {
        rt.phase = InstanceRuntime.Phase.BACKOFF;
        rt.restartAt = System.currentTimeMillis() + delayMs;
        scheduler.schedule(() -> {
            boolean wanted = store.read(s -> s.instances.stream().anyMatch(i -> i.id.equals(rt.instanceId) && i.desiredRunning));
            if (wanted && rt.phase == InstanceRuntime.Phase.BACKOFF && !shuttingDown) start(rt.instanceId);
        }, delayMs, TimeUnit.MILLISECONDS);
    }

    /** Saves settings changed inside the instance (commands, in-game) as instance overrides. */
    private void persistInstanceSettings(String instanceId) {
        var dir = config.instanceDir(instanceId);
        try {
            var defaults = zenithDefaults(dir);
            var configFile = dir.resolve("config.json");
            if (defaults == null || !Files.exists(configFile)) return;
            var saved = Json.readObject(configFile);
            var ctx = context(instanceId);
            var overrides = ConfigComposer.extractInstanceOverrides(defaults, ctx, saved);
            store.update(s -> s.instances.stream().filter(i -> i.id.equals(instanceId)).findFirst()
                .ifPresent(i -> i.settings = overrides));
            var instanceDefaults = dir.resolve("config.defaults.json");
            if (Files.exists(instanceDefaults)) SecureFiles.copy(instanceDefaults, config.zenithDefaultsFile());
            var instanceSchema = dir.resolve("config.schema.json");
            if (Files.exists(instanceSchema)) SecureFiles.copy(instanceSchema, config.zenithSchemaFile());
        } catch (Exception e) {
            LOG.warn("Failed saving settings of instance {}", instanceId, e);
        }
    }

    public JsonObject zenithDefaults(Path instanceDir) {
        for (var candidate : new Path[] { instanceDir.resolve("config.defaults.json"), config.zenithDefaultsFile() }) {
            try {
                if (Files.exists(candidate)) return Json.readObject(candidate);
            } catch (Exception e) {
                LOG.warn("Unreadable Zenith defaults {}", candidate, e);
            }
        }
        return null;
    }

    // --- observing ---

    private void readStdout(InstanceRuntime rt, Process process) {
        try (var reader = new BufferedReader(new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                // Zenith stops printing to stdout while we're subscribed to its events
                if (line.isBlank()) continue;
                var plain = ANSI.matcher(line).replaceAll("");
                var m = LOG_LINE.matcher(plain);
                if (m.matches()) {
                    appendLog(rt, m.group(1), m.group(2), m.group(3), LOG_PREFIX.matcher(line).replaceFirst(""));
                } else {
                    appendLog(rt, "Stdout", "INFO", plain, line);
                }
            }
        } catch (IOException ignored) {
            // process ended
        }
    }

    private void streamEvents(InstanceRuntime rt, Process process) {
        while (process.isAlive() && rt.process == process) {
            try {
                var response = http.send(request(rt, "/v1/events").GET().build(), HttpResponse.BodyHandlers.ofLines());
                if (response.statusCode() != 200) throw new IOException("status " + response.statusCode());
                rt.eventsConnected = true;
                try (var lines = response.body()) {
                    lines.forEach(line -> {
                        if (!line.startsWith("data: ")) return;
                        var json = JsonParser.parseString(line.substring(6)).getAsJsonObject();
                        switch (json.get("type").getAsString()) {
                            case "log" -> appendLog(rt, str(json, "logger"), str(json, "level"), str(json, "text"), str(json, "ansi"), json.get("ts").getAsLong());
                            // what Zenith's own Discord bot would have posted
                            case "notification" -> events.publish(json.get("type").getAsString(), rt.instanceId, json);
                            default -> {}
                        }
                    });
                }
            } catch (Exception e) {
                // control server not up yet, or connection dropped
            } finally {
                rt.eventsConnected = false;
            }
            try {
                Thread.sleep(1000);
            } catch (InterruptedException e) {
                return;
            }
        }
    }

    private void pollAll() {
        for (var rt : runtimes.values()) {
            var phase = rt.phase;
            if (phase != InstanceRuntime.Phase.STARTING && phase != InstanceRuntime.Phase.RUNNING) continue;
            http.sendAsync(request(rt, "/v1/status").timeout(Duration.ofSeconds(3)).GET().build(), HttpResponse.BodyHandlers.ofString())
                .thenAccept(response -> {
                    if (response.statusCode() != 200) return;
                    var status = JsonParser.parseString(response.body()).getAsJsonObject();
                    boolean changed = !status.equals(rt.status) || rt.phase == InstanceRuntime.Phase.STARTING;
                    rt.status = status;
                    if (rt.phase == InstanceRuntime.Phase.STARTING) rt.phase = InstanceRuntime.Phase.RUNNING;
                    // a fresh process isn't connected yet; only a real disconnect means it should stay offline
                    if (status.get("connected").getAsBoolean()) setLastConnected(rt.instanceId, true);
                    else if (status.has("lastDisconnectReason")) setLastConnected(rt.instanceId, false);
                    if (changed) publishRuntime(rt);
                });
        }
    }

    private void setLastConnected(String instanceId, boolean connected) {
        boolean differs = store.read(s -> s.instances.stream().anyMatch(i -> i.id.equals(instanceId) && i.lastConnected != connected));
        if (differs) store.update(s -> s.instances.stream().filter(i -> i.id.equals(instanceId)).forEach(i -> i.lastConnected = connected));
    }

    // --- helpers ---

    private void appendLog(InstanceRuntime rt, String logger, String level, String text, String ansi) {
        appendLog(rt, logger, level, text, ansi, System.currentTimeMillis());
    }

    private void appendLog(InstanceRuntime rt, String logger, String level, String text, String ansi, long ts) {
        var line = rt.logs.append(ts, logger, level, text, ansi);
        events.publish("log", rt.instanceId, logLineJson(line));
    }

    public static JsonObject logLineJson(LogBuffer.Line line) {
        var json = new JsonObject();
        json.addProperty("seq", line.seq());
        json.addProperty("ts", line.ts());
        json.addProperty("logger", line.logger());
        json.addProperty("level", line.level());
        json.addProperty("text", line.text());
        json.addProperty("ansi", line.ansi());
        return json;
    }

    private void publishRuntime(InstanceRuntime rt) {
        events.publish("runtime", rt.instanceId, rt.toJson());
    }

    private HttpRequest.Builder request(InstanceRuntime rt, String path) {
        int port = store.read(s -> s.instances.stream().filter(i -> i.id.equals(rt.instanceId)).findFirst().map(i -> i.controlPort).orElse(0));
        return HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path))
            .header("Authorization", "Bearer " + rt.token);
    }

    public ConfigComposer.Context context(String instanceId) {
        var ctx = store.read(s -> {
            var instance = s.instances.stream().filter(i -> i.id.equals(instanceId)).findFirst()
                .orElseThrow(() -> new IllegalArgumentException("Unknown instance " + instanceId));
            var server = s.servers.stream().filter(x -> x.id.equals(instance.serverId)).findFirst()
                .orElseThrow(() -> new IllegalStateException("Instance server was deleted"));
            var account = s.accounts.stream().filter(a -> a.id.equals(instance.accountId)).findFirst()
                .orElseThrow(() -> new IllegalStateException("Instance account was deleted"));
            return new ConfigComposer.Context(instance, server, account, s.globalSettings, s.settings, null, config.cheatsDisabled());
        });
        return new ConfigComposer.Context(ctx.instance(), ctx.server(), ctx.account(), ctx.globalSettings(), ctx.managerSettings(), joinLists.apply(instanceId), ctx.cheatsDisabled());
    }

    private volatile java.util.function.Function<String, ConfigComposer.JoinLists> joinLists = id -> ConfigComposer.JoinLists.EMPTY;

    public void setJoinLists(java.util.function.Function<String, ConfigComposer.JoinLists> provider) {
        this.joinLists = provider;
    }

    private void setDesiredRunning(String instanceId, boolean running) {
        store.update(s -> s.instances.stream().filter(i -> i.id.equals(instanceId)).forEach(i -> i.desiredRunning = running));
    }

    private static String str(JsonObject json, String key) {
        var e = json.get(key);
        return e == null || e.isJsonNull() ? null : e.getAsString();
    }
}
