package com.zenith.control.plugins;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.zenith.control.ControlServer;
import com.zenith.control.boot.PluginClassLoader;
import net.kyori.adventure.text.logger.slf4j.ComponentLogger;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

import static com.zenith.Globals.PLUGIN_LOG;

/** ZenithHub plugins inside an instance process: loads their bot side and connects it to the control API. */
public final class BotPlugins {
    private static final List<Loaded> LOADED = new CopyOnWriteArrayList<>();
    private static final List<String> ERRORS = new CopyOnWriteArrayList<>();
    private static volatile JsonArray installed;
    private static volatile boolean started;

    private BotPlugins() {}

    /** Called by {@link com.zenith.control.boot.PluginLaunch} before ZenithProxy starts. */
    public static void install(String file) throws Exception {
        installed = JsonParser.parseString(Files.readString(Path.of(file))).getAsJsonObject().getAsJsonArray("plugins");
    }

    /** Called once ZenithProxy is up. Does nothing when the instance runs without plugins. */
    public static synchronized void start() {
        var plugins = installed;
        if (plugins == null || started) return;
        started = true;
        for (var element : plugins) {
            var spec = element.getAsJsonObject();
            var id = spec.get("id").getAsString();
            var main = spec.has("main") && !spec.get("main").isJsonNull() ? spec.get("main").getAsString() : null;
            if (main == null) continue;
            try {
                var ctx = new Context(spec);
                Files.createDirectories(ctx.dataDir());
                var plugin = (ZenithHubBotPlugin) Class.forName(main, true, Thread.currentThread().getContextClassLoader())
                    .getDeclaredConstructor().newInstance();
                plugin.onLoad(ctx);
                LOADED.add(new Loaded(id, plugin, ctx));
                PLUGIN_LOG.info("Loaded plugin {} {}", id, ctx.version());
            } catch (Throwable e) {
                PLUGIN_LOG.error("Plugin {} failed to load", id, e);
                ERRORS.add(id + ": " + e);
            }
        }
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            for (var loaded : LOADED) {
                try {
                    loaded.plugin.onStop();
                } catch (Throwable e) {
                    PLUGIN_LOG.warn("Plugin {} failed to stop", loaded.id, e);
                }
            }
        }, "plugins-stop"));
    }

    /** For {@code /v1/status}: which plugins run here and what went wrong. Null without plugins. */
    public static JsonObject status() {
        if (installed == null) return null;
        var json = new JsonObject();
        var loaded = new JsonArray();
        for (var l : LOADED) loaded.add(l.id);
        json.add("loaded", loaded);
        var errors = new JsonArray();
        for (var e : ERRORS) errors.add(e);
        for (var e : PluginClassLoader.ERRORS) errors.add(e);
        json.add("errors", errors);
        return json;
    }

    /** The admin changed a plugin's settings; the manager pushes them to running instances. */
    public static void settingsChanged(String id, JsonObject settings) {
        for (var loaded : LOADED) {
            if (!loaded.id.equals(id)) continue;
            loaded.ctx.settings = settings;
            try {
                loaded.plugin.onSettingsChanged(settings);
            } catch (Throwable e) {
                PLUGIN_LOG.warn("Plugin {} failed applying its settings", id, e);
            }
        }
    }

    /** {@code /v1/plugin/<id>/<path>}; null when nothing answers it. */
    public static BotPluginContext.RouteHandler route(String id, String path) {
        for (var loaded : LOADED) {
            if (loaded.id.equals(id)) return loaded.ctx.routes.get(path);
        }
        return null;
    }

    private record Loaded(String id, ZenithHubBotPlugin plugin, Context ctx) {}

    private static final class Context implements BotPluginContext {
        private final String id;
        private final String version;
        private final String instanceId;
        private final Path dataDir;
        private final ComponentLogger logger;
        private final Map<String, RouteHandler> routes = new ConcurrentHashMap<>();
        private volatile JsonObject settings;

        Context(JsonObject spec) {
            id = spec.get("id").getAsString();
            version = spec.get("version").getAsString();
            instanceId = spec.has("instanceId") ? spec.get("instanceId").getAsString() : "";
            dataDir = Path.of(spec.get("dataDir").getAsString());
            settings = spec.has("settings") ? spec.getAsJsonObject("settings") : new JsonObject();
            logger = ComponentLogger.logger("Plugin/" + id);
        }

        @Override public String id() { return id; }
        @Override public String version() { return version; }
        @Override public String instanceId() { return instanceId; }
        @Override public JsonObject settings() { return settings.deepCopy(); }
        @Override public Path dataDir() { return dataDir; }
        @Override public ComponentLogger logger() { return logger; }

        @Override
        public void emit(String event, JsonElement data) {
            var json = new JsonObject();
            json.addProperty("type", "plugin");
            json.addProperty("plugin", id);
            json.addProperty("event", event);
            json.addProperty("ts", System.currentTimeMillis());
            if (data != null) json.add("data", data);
            ControlServer.INSTANCE.publish(json);
        }

        @Override
        public void route(String path, RouteHandler handler) {
            routes.put(path.replaceAll("^/+", ""), handler);
        }
    }
}
