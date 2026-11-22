package com.zenith.manager.plugins;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;
import com.zenith.manager.EventHub;
import com.zenith.manager.ManagerConfig;
import com.zenith.manager.auth.Capability;
import com.zenith.manager.auth.Passwords;
import com.zenith.manager.boot.BootInfo;
import com.zenith.manager.boot.PluginManifest;
import com.zenith.manager.boot.PluginRegistry;
import com.zenith.manager.instances.Supervisor;
import com.zenith.manager.state.State;
import com.zenith.manager.util.Json;
import com.zenith.manager.util.SecureFiles;
import io.javalin.Javalin;
import io.javalin.config.JavalinConfig;
import io.javalin.http.Context;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InputStream;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;
import java.util.zip.ZipInputStream;

/**
 * ZenithHub plugins: zips the admin uploads, with a manager side (Java, loaded at start by
 * {@link com.zenith.manager.boot.Boot}), a bot side (Java, loaded into every instance process) and a web side
 * (scripts every signed-in browser loads). No sandbox: plugins are trusted code. See docs/plugins.md.
 */
public final class PluginService {
    private static final Logger LOG = LoggerFactory.getLogger("Plugins");
    /** Asks the entrypoint script to start the manager again. */
    public static final int RESTART_EXIT_CODE = 75;
    public static final long MAX_UPLOAD = 128L * 1024 * 1024;
    private static final long MAX_UNPACKED = 512L * 1024 * 1024;
    private static final int MAX_ENTRIES = 5000;
    private static final long STAGE_TTL_MS = 30 * 60_000L;

    /** An uploaded zip waiting for the admin to confirm it. */
    private record Staged(String token, Path dir, PluginManifest manifest, long createdAt) {}

    private record Loaded(String id, ZenithHubPlugin plugin, Ctx ctx) {}

    /** How an instance must start for its plugins: null without bot plugins. */
    public record Launch(Path file, String key) {}

    private final ManagerConfig config;
    private final EventHub events;
    private final Path dataDir;
    private final Path pluginsDir;
    private final Path stagingDir;
    private final Path settingsDir;
    private final Object lock = new Object();
    private final List<PluginRegistry.Entry> entries;
    /** Manifests of the installed revisions. */
    private final Map<String, PluginManifest> manifests = new ConcurrentHashMap<>();
    private final Map<String, String> readErrors = new ConcurrentHashMap<>();
    private final Map<String, Staged> staged = new ConcurrentHashMap<>();
    private final List<Loaded> loaded = new CopyOnWriteArrayList<>();
    private final List<Consumer<JavalinConfig>> javalinHooks = new CopyOnWriteArrayList<>();
    private final List<Map.Entry<String, Consumer<Javalin>>> routeHooks = new CopyOnWriteArrayList<>();
    private final Map<String, List<PluginContext.BotEventListener>> botListeners = new ConcurrentHashMap<>();
    private volatile Supervisor supervisor;
    private volatile Hub hub;
    private volatile boolean loading;

    public PluginService(ManagerConfig config, EventHub events) throws IOException {
        this.config = config;
        this.events = events;
        this.dataDir = config.dataDir();
        this.pluginsDir = PluginRegistry.pluginsDir(dataDir);
        this.stagingDir = pluginsDir.resolve(".staging");
        this.settingsDir = dataDir.resolve("manager").resolve("secrets").resolve("plugin-settings");
        this.entries = PluginRegistry.read(dataDir);
        for (var entry : entries) readManifest(entry);
        cleanUp();
    }

    public void setSupervisor(Supervisor supervisor) {
        this.supervisor = supervisor;
    }

    private void readManifest(PluginRegistry.Entry entry) {
        try {
            manifests.put(entry.id, PluginManifest.read(PluginRegistry.codeDir(dataDir, entry)));
            readErrors.remove(entry.id);
        } catch (Exception e) {
            manifests.remove(entry.id);
            readErrors.put(entry.id, "Couldn't read the plugin: " + e.getMessage());
        }
    }

    /** Nothing runs plugin code yet: drop old revisions, removed plugins and unfinished uploads. */
    private void cleanUp() {
        try {
            SecureFiles.deleteRecursively(stagingDir);
            if (!Files.isDirectory(pluginsDir)) return;
            try (var dirs = Files.list(pluginsDir)) {
                for (var dir : dirs.toList()) {
                    var name = dir.getFileName().toString();
                    var entry = entries.stream().filter(e -> e.id.equals(name)).findFirst().orElse(null);
                    if (entry == null) {
                        SecureFiles.deleteRecursively(dir);
                        continue;
                    }
                    try (var revisions = Files.list(dir)) {
                        for (var rev : revisions.toList()) {
                            if (!rev.getFileName().toString().equals(entry.revision)) SecureFiles.deleteRecursively(rev);
                        }
                    }
                }
            }
        } catch (IOException e) {
            LOG.warn("Couldn't clean up old plugin files: {}", e.getMessage());
        }
    }

    // --- the manager side ---

    /** Creates the manager side of every plugin {@link com.zenith.manager.boot.Boot} put on the class path. */
    public void load(Hub hub) {
        this.hub = hub;
        loading = true;
        try {
            for (var id : BootInfo.LOADED.keySet()) {
                var manifest = BootInfo.MANIFESTS.get(id);
                if (manifest == null || manifest.manager().main() == null) continue;
                var entry = entry(id);
                if (entry == null) continue;
                try {
                    var ctx = new Ctx(id, manifest, PluginRegistry.codeDir(dataDir, entry));
                    Files.createDirectories(ctx.dataDir());
                    var plugin = (ZenithHubPlugin) Class.forName(manifest.manager().main(), true, Thread.currentThread().getContextClassLoader())
                        .getDeclaredConstructor().newInstance();
                    plugin.onLoad(ctx);
                    loaded.add(new Loaded(id, plugin, ctx));
                    LOG.info("Loaded plugin {} {}", id, manifest.version());
                } catch (Throwable e) {
                    LOG.error("Plugin {} failed to load", id, e);
                    BootInfo.error(id, "Failed to load: " + e);
                }
            }
        } finally {
            loading = false;
        }
    }

    public void configureJavalin(JavalinConfig cfg) {
        for (var hook : javalinHooks) {
            try {
                hook.accept(cfg);
            } catch (Throwable e) {
                LOG.error("A plugin failed configuring the web server", e);
            }
        }
    }

    public void registerRoutes(Javalin app) {
        for (var hook : routeHooks) {
            try {
                hook.getValue().accept(app);
            } catch (Throwable e) {
                LOG.error("Plugin {} failed adding its routes", hook.getKey(), e);
                BootInfo.error(hook.getKey(), "Failed adding its routes: " + e);
            }
        }
    }

    /** Everything is up; tells the plugins and marks the start as one that worked. */
    public void started() {
        for (var l : loaded) {
            try {
                l.plugin.onStarted();
            } catch (Throwable e) {
                LOG.error("Plugin {} failed in onStarted", l.id, e);
                BootInfo.error(l.id, "Failed after the start: " + e);
            }
        }
        BootInfo.started(dataDir);
    }

    public void stop() {
        for (var l : loaded) {
            try {
                l.plugin.onStop();
            } catch (Throwable e) {
                LOG.warn("Plugin {} failed to stop", l.id, e);
            }
        }
    }

    /** {@code plugin} events from a bot: to the plugin's manager side and to the web UI. */
    public void botEvent(String instanceId, JsonObject json) {
        var id = json.has("plugin") ? json.get("plugin").getAsString() : "";
        var event = json.has("event") ? json.get("event").getAsString() : "";
        var data = json.has("data") ? json.get("data") : JsonNull.INSTANCE;
        for (var listener : botListeners.getOrDefault(id, List.of())) {
            try {
                listener.onEvent(instanceId, event, data);
            } catch (Throwable e) {
                LOG.warn("Plugin {} failed handling bot event {}", id, event, e);
            }
        }
        events.publish("plugin", instanceId, json);
    }

    // --- the bot side ---

    /**
     * Writes the instance's plugin list and says how to start it, or returns null when no plugin has a bot side.
     * Instances with bot plugins run ZenithProxy on the JVM, since the native image can't load jars.
     */
    public Launch launchFor(String instanceId) throws IOException {
        var dir = config.instanceDir(instanceId);
        var file = dir.resolve("plugins.json");
        var list = new JsonArray();
        var key = new StringBuilder();
        synchronized (lock) {
            for (var entry : entries) {
                var manifest = manifests.get(entry.id);
                if (!entry.enabled || manifest == null || !manifest.bot().present()) continue;
                var code = PluginRegistry.codeDir(dataDir, entry);
                var spec = new JsonObject();
                spec.addProperty("id", entry.id);
                spec.addProperty("version", manifest.version());
                spec.addProperty("instanceId", instanceId);
                var jars = new JsonArray();
                for (var jar : manifest.bot().jars()) jars.add(code.resolve(jar).toString());
                spec.add("jars", jars);
                if (manifest.bot().main() != null) spec.addProperty("main", manifest.bot().main());
                var mixins = new JsonArray();
                manifest.bot().mixins().forEach(mixins::add);
                spec.add("mixins", mixins);
                spec.add("settings", effectiveSettings(entry.id, manifest));
                spec.addProperty("dataDir", dir.resolve("plugin-data").resolve(entry.id).toString());
                list.add(spec);
                key.append(entry.id).append('@').append(entry.revision).append(';');
            }
        }
        if (list.isEmpty()) {
            Files.deleteIfExists(file);
            return null;
        }
        var root = new JsonObject();
        root.add("plugins", list);
        Files.createDirectories(dir);
        SecureFiles.writeString(file, Json.GSON.toJson(root));
        return new Launch(file, key.toString());
    }

    /** What {@link #launchFor} would give an instance now, to spot ones that run older plugins. */
    public String botKey() {
        var key = new StringBuilder();
        synchronized (lock) {
            for (var entry : entries) {
                var manifest = manifests.get(entry.id);
                if (entry.enabled && manifest != null && manifest.bot().present()) key.append(entry.id).append('@').append(entry.revision).append(';');
            }
        }
        return key.toString();
    }

    // --- the web side ---

    /** For the overview: the scripts and styles of every plugin that's on, with its public settings. */
    public JsonArray webPlugins() {
        var arr = new JsonArray();
        synchronized (lock) {
            for (var entry : entries) {
                var manifest = manifests.get(entry.id);
                if (!entry.enabled || manifest == null) continue;
                var json = new JsonObject();
                json.addProperty("id", entry.id);
                json.addProperty("name", manifest.name());
                json.addProperty("version", manifest.version());
                var scripts = new JsonArray();
                manifest.web().scripts().forEach(s -> scripts.add(assetUrl(entry, s)));
                json.add("scripts", scripts);
                var styles = new JsonArray();
                manifest.web().styles().forEach(s -> styles.add(assetUrl(entry, s)));
                json.add("styles", styles);
                var settings = new JsonObject();
                var effective = effectiveSettings(entry.id, manifest);
                for (var s : manifest.settings()) if (s.isPublic()) settings.add(s.key(), effective.get(s.key()));
                json.add("settings", settings);
                arr.add(json);
            }
        }
        return arr;
    }

    private static String assetUrl(PluginRegistry.Entry entry, String path) {
        // the revision in the query lets browsers keep the file until the plugin changes
        return "/plugin-assets/" + entry.id + "/" + path.substring("web/".length()) + "?v=" + entry.revision;
    }

    /** A file of a plugin that's on, from its web/ folder; null if there's no such file. */
    public Path webFile(String id, String path) {
        synchronized (lock) {
            var entry = entry(id);
            if (entry == null || !entry.enabled) return null;
            var web = PluginRegistry.codeDir(dataDir, entry).resolve("web").normalize();
            var file = web.resolve(path).normalize();
            if (!file.startsWith(web) || !Files.isRegularFile(file)) return null;
            return file;
        }
    }

    // --- admin ---

    public JsonObject list() {
        var json = new JsonObject();
        var arr = new JsonArray();
        var running = runningInstances();
        var key = botKey();
        boolean restart = false;
        synchronized (lock) {
            for (var entry : entries) {
                var manifest = manifests.get(entry.id);
                var view = manifest != null ? manifestView(manifest) : new JsonObject();
                view.addProperty("id", entry.id);
                view.addProperty("enabled", entry.enabled);
                view.addProperty("installedAt", entry.installedAt);
                view.addProperty("updatedAt", entry.updatedAt);
                var errors = new JsonArray();
                if (readErrors.containsKey(entry.id)) errors.add(readErrors.get(entry.id));
                BootInfo.ERRORS.getOrDefault(entry.id, List.of()).forEach(errors::add);
                // what each instance's bot side reported
                if (manifest != null && manifest.bot().present()) {
                    for (var id : running) {
                        var status = supervisor.runtime(id).status();
                        if (status == null || !status.has("plugins")) continue;
                        for (var e : status.getAsJsonObject("plugins").getAsJsonArray("errors")) {
                            var text = e.getAsString();
                            if (text.startsWith(entry.id + ":") || !text.contains(":")) errors.add(instanceName(id) + ": " + text);
                        }
                    }
                }
                view.add("errors", errors);
                var loadedRevision = BootInfo.LOADED.get(entry.id);
                boolean wantsManager = entry.enabled && manifest != null && manifest.manager().present();
                boolean needsRestart = wantsManager ? !entry.revision.equals(loadedRevision) : loadedRevision != null;
                view.addProperty("needsRestart", needsRestart && BootInfo.safeMode == null);
                view.addProperty("managerLoaded", loadedRevision != null && loadedRevision.equals(entry.revision));
                restart |= needsRestart;
                arr.add(view);
            }
            // removed while still loaded
            for (var id : BootInfo.LOADED.keySet()) if (entry(id) == null) restart = true;
        }
        json.add("plugins", arr);
        json.addProperty("needsRestart", restart && BootInfo.safeMode == null);
        json.addProperty("canRestart", "1".equals(System.getenv("ZENITH_SUPERVISED")));
        json.addProperty("safeMode", BootInfo.safeMode);
        var general = new JsonArray();
        BootInfo.ERRORS.getOrDefault("", List.of()).forEach(general::add);
        json.add("errors", general);
        // instances started before the latest bot-side change
        var stale = new JsonArray();
        for (var id : running) {
            if (!key.equals(supervisor.pluginKey(id))) {
                var item = new JsonObject();
                item.addProperty("id", id);
                item.addProperty("name", instanceName(id));
                stale.add(item);
            }
        }
        json.add("staleInstances", stale);
        json.addProperty("jvm", config.zenithJvmCommand() != null);
        return json;
    }

    private JsonObject manifestView(PluginManifest m) {
        var json = new JsonObject();
        json.addProperty("id", m.id());
        json.addProperty("name", m.name());
        json.addProperty("version", m.version());
        json.addProperty("description", m.description());
        var authors = new JsonArray();
        m.authors().forEach(authors::add);
        json.add("authors", authors);
        json.addProperty("url", m.url().isEmpty() ? null : m.url());
        var parts = new JsonObject();
        parts.add("manager", partView(m.manager()));
        parts.add("bot", partView(m.bot()));
        var web = new JsonObject();
        web.addProperty("present", m.web().present());
        web.addProperty("scripts", m.web().scripts().size());
        web.addProperty("styles", m.web().styles().size());
        parts.add("web", web);
        json.add("parts", parts);
        json.addProperty("hasSettings", !m.settings().isEmpty());
        return json;
    }

    private static JsonObject partView(PluginManifest.Part part) {
        var json = new JsonObject();
        json.addProperty("present", part.present());
        json.addProperty("mixins", part.mixins().size());
        json.addProperty("jars", part.jars().size());
        return json;
    }

    /** Unpacks an uploaded zip into the staging area and reads its manifest; nothing is installed yet. */
    public JsonObject stage(InputStream zip) throws IOException {
        staged.values().removeIf(s -> {
            if (System.currentTimeMillis() - s.createdAt < STAGE_TTL_MS) return false;
            try {
                SecureFiles.deleteRecursively(s.dir);
            } catch (IOException ignored) {
                // cleaned up on the next start
            }
            return true;
        });
        var token = Passwords.randomToken(16);
        var dir = stagingDir.resolve(token);
        Files.createDirectories(dir);
        try {
            unzip(zip, dir);
            var root = rootOf(dir);
            var manifest = PluginManifest.read(root);
            staged.put(token, new Staged(token, root, manifest, System.currentTimeMillis()));
            var json = new JsonObject();
            json.addProperty("token", token);
            json.add("plugin", manifestView(manifest));
            PluginRegistry.Entry existing;
            synchronized (lock) {
                existing = entry(manifest.id());
            }
            if (existing != null) {
                var current = manifests.get(existing.id);
                json.addProperty("replaces", current != null ? current.version() : "?");
            }
            return json;
        } catch (IOException | RuntimeException e) {
            SecureFiles.deleteRecursively(dir);
            throw e;
        }
    }

    /** plugin.json at the top of the zip, or in its only folder (zipping a folder does that). */
    static Path rootOf(Path dir) throws IOException {
        if (Files.exists(dir.resolve(PluginManifest.FILE))) return dir;
        try (var children = Files.list(dir)) {
            var list = children.filter(p -> !p.getFileName().toString().equals("__MACOSX")).toList();
            if (list.size() == 1 && Files.isDirectory(list.getFirst()) && Files.exists(list.getFirst().resolve(PluginManifest.FILE))) return list.getFirst();
        }
        throw new IllegalArgumentException("The zip has no plugin.json at the top");
    }

    static void unzip(InputStream in, Path dir) throws IOException {
        long total = 0;
        int count = 0;
        var root = dir.normalize();
        boolean any = false;
        try (var zip = new ZipInputStream(in)) {
            var buffer = new byte[64 * 1024];
            for (var entry = zip.getNextEntry(); entry != null; entry = zip.getNextEntry()) {
                any = true;
                if (++count > MAX_ENTRIES) throw new IllegalArgumentException("The zip has more than " + MAX_ENTRIES + " files");
                var name = entry.getName().replace('\\', '/');
                var target = root.resolve(name).normalize();
                if (name.startsWith("/") || !target.startsWith(root) || target.equals(root)) {
                    if (target.equals(root)) continue;
                    throw new IllegalArgumentException("The zip has a file outside its folder: " + name);
                }
                if (entry.isDirectory()) {
                    Files.createDirectories(target);
                    continue;
                }
                Files.createDirectories(target.getParent());
                try (var out = Files.newOutputStream(target)) {
                    int n;
                    while ((n = zip.read(buffer)) > 0) {
                        total += n;
                        if (total > MAX_UNPACKED) throw new IllegalArgumentException("The plugin is larger than " + (MAX_UNPACKED >> 20) + " MB unpacked");
                        out.write(buffer, 0, n);
                    }
                }
            }
        } catch (java.util.zip.ZipException e) {
            throw new IllegalArgumentException("That isn't a valid zip file: " + e.getMessage());
        }
        if (!any) throw new IllegalArgumentException("That isn't a zip file, or it's empty");
    }

    /** Installs (or updates) a staged plugin. */
    public String install(String token, boolean enable) throws IOException {
        var s = staged.remove(token);
        if (s == null) throw new IllegalArgumentException("That upload expired. Upload the zip again.");
        var revision = Long.toString(System.currentTimeMillis(), 36) + Passwords.randomToken(2).toLowerCase(java.util.Locale.ROOT).replaceAll("[^a-z0-9]", "");
        synchronized (lock) {
            var entry = entry(s.manifest.id());
            var target = pluginsDir.resolve(s.manifest.id()).resolve(revision);
            Files.createDirectories(target.getParent());
            Files.move(s.dir, target);
            long now = System.currentTimeMillis();
            if (entry == null) {
                entry = new PluginRegistry.Entry();
                entry.id = s.manifest.id();
                entry.installedAt = now;
                entries.add(entry);
            }
            var previous = entry.revision;
            entry.revision = revision;
            entry.updatedAt = now;
            entry.enabled = enable || entry.enabled;
            PluginRegistry.write(dataDir, entries);
            readManifest(entry);
            // the old files stay while nothing has loaded them, until the next start otherwise
            if (previous != null && !previous.equals(BootInfo.LOADED.get(entry.id)) && !botUses(entry.id + "@" + previous)) {
                SecureFiles.deleteRecursively(pluginsDir.resolve(entry.id).resolve(previous));
            }
        }
        // the staging folder may have held the plugin one level down
        SecureFiles.deleteRecursively(stagingDir.resolve(token));
        LOG.info("Installed plugin {} {}", s.manifest.id(), s.manifest.version());
        events.publish("changed", null, new JsonObject());
        return s.manifest.id();
    }

    /** Drops an upload the admin decided not to install. */
    public void discard(String token) throws IOException {
        var s = staged.remove(token);
        if (s != null) SecureFiles.deleteRecursively(stagingDir.resolve(token));
    }

    private boolean botUses(String idAtRevision) {
        var supervisor = this.supervisor;
        if (supervisor == null) return false;
        for (var id : runningInstances()) {
            var key = supervisor.pluginKey(id);
            if (key != null && key.contains(idAtRevision + ";")) return true;
        }
        return false;
    }

    public void setEnabled(String id, boolean enabled) throws IOException {
        synchronized (lock) {
            var entry = requireEntry(id);
            entry.enabled = enabled;
            PluginRegistry.write(dataDir, entries);
        }
        events.publish("changed", null, new JsonObject());
    }

    /** Uninstalls; the files go at the next start if the manager or a bot still runs them. */
    public void remove(String id, boolean deleteData) throws IOException {
        PluginRegistry.Entry entry;
        synchronized (lock) {
            entry = requireEntry(id);
            entries.remove(entry);
            manifests.remove(id);
            readErrors.remove(id);
            PluginRegistry.write(dataDir, entries);
        }
        if (!BootInfo.LOADED.containsKey(id) && !botUses(id + "@" + entry.revision)) {
            SecureFiles.deleteRecursively(pluginsDir.resolve(id));
        }
        if (deleteData) {
            SecureFiles.deleteRecursively(dataDir.resolve("plugin-data").resolve(id));
            Files.deleteIfExists(settingsDir.resolve(id + ".json"));
            var instances = dataDir.resolve("instances");
            if (Files.isDirectory(instances)) {
                try (var dirs = Files.list(instances)) {
                    for (var dir : dirs.toList()) SecureFiles.deleteRecursively(dir.resolve("plugin-data").resolve(id));
                }
            }
        }
        LOG.info("Removed plugin {}", id);
        events.publish("changed", null, new JsonObject());
    }

    // --- settings ---

    /** The form and its values; secrets only say whether they're set. */
    public JsonObject settingsView(String id) {
        PluginManifest manifest;
        synchronized (lock) {
            requireEntry(id);
            manifest = manifests.get(id);
        }
        if (manifest == null) throw new IllegalStateException("The plugin's files can't be read");
        var values = effectiveSettings(id, manifest);
        var fields = new JsonArray();
        for (var s : manifest.settings()) {
            var f = new JsonObject();
            f.addProperty("key", s.key());
            f.addProperty("label", s.label());
            f.addProperty("type", s.type());
            f.addProperty("description", s.description());
            if (s.min() != null) f.addProperty("min", s.min());
            if (s.max() != null) f.addProperty("max", s.max());
            var options = new JsonArray();
            for (var o : s.options()) {
                var oj = new JsonObject();
                oj.addProperty("value", o.value());
                oj.addProperty("label", o.label());
                options.add(oj);
            }
            f.add("options", options);
            if (s.type().equals("secret")) {
                var value = values.get(s.key());
                f.addProperty("set", value != null && !value.isJsonNull() && !value.getAsString().isEmpty());
            } else {
                f.add("value", values.get(s.key()));
            }
            fields.add(f);
        }
        var json = new JsonObject();
        json.add("fields", fields);
        return json;
    }

    /** Checks and saves new values, then tells the manager side and running bots. A blank secret keeps the old one. */
    public void updateSettings(String id, JsonObject values) throws Exception {
        PluginManifest manifest;
        synchronized (lock) {
            requireEntry(id);
            manifest = manifests.get(id);
        }
        if (manifest == null) throw new IllegalStateException("The plugin's files can't be read");
        var saved = savedSettings(id);
        for (var s : manifest.settings()) {
            if (!values.has(s.key())) continue;
            var value = values.get(s.key());
            if (value == null || value.isJsonNull()) {
                if (!s.type().equals("secret")) saved.remove(s.key());
                continue;
            }
            saved.add(s.key(), checked(s, value));
        }
        Files.createDirectories(settingsDir);
        SecureFiles.writeString(settingsDir.resolve(id + ".json"), Json.GSON.toJson(saved));
        var effective = effectiveSettings(id, manifest);
        for (var l : loaded) {
            if (!l.id.equals(id)) continue;
            try {
                l.plugin.onSettingsChanged(effective.deepCopy());
            } catch (Throwable e) {
                LOG.warn("Plugin {} failed applying its settings", id, e);
            }
        }
        var supervisor = this.supervisor;
        if (supervisor != null && manifest.bot().present()) {
            for (var instanceId : runningInstances()) {
                var key = supervisor.pluginKey(instanceId);
                if (key == null || !key.contains(id + "@")) continue;
                try {
                    supervisor.pushPluginSettings(instanceId, id, effective);
                } catch (Exception e) {
                    LOG.warn("Couldn't send {}'s settings to {}: {}", id, instanceId, e.getMessage());
                }
            }
        }
        events.publish("changed", null, new JsonObject());
    }

    private static JsonElement checked(PluginManifest.Setting s, JsonElement value) {
        if (!value.isJsonPrimitive()) throw new IllegalArgumentException(s.label() + ": wrong kind of value");
        var p = value.getAsJsonPrimitive();
        return switch (s.type()) {
            case "boolean" -> {
                if (!p.isBoolean()) throw new IllegalArgumentException(s.label() + " must be on or off");
                yield p;
            }
            case "number" -> {
                double d;
                try {
                    d = p.getAsDouble();
                } catch (NumberFormatException e) {
                    throw new IllegalArgumentException(s.label() + " must be a number");
                }
                if (!Double.isFinite(d)) throw new IllegalArgumentException(s.label() + " must be a number");
                if (s.min() != null && d < s.min()) throw new IllegalArgumentException(s.label() + " must be at least " + fmt(s.min()));
                if (s.max() != null && d > s.max()) throw new IllegalArgumentException(s.label() + " must be at most " + fmt(s.max()));
                yield d == Math.rint(d) && Math.abs(d) < 1e15 ? new JsonPrimitive((long) d) : new JsonPrimitive(d);
            }
            case "select" -> {
                var v = p.getAsString();
                if (s.options().stream().noneMatch(o -> o.value().equals(v))) throw new IllegalArgumentException(s.label() + ": pick one of the options");
                yield new JsonPrimitive(v);
            }
            default -> {
                var v = p.getAsString();
                if (v.length() > 20_000) throw new IllegalArgumentException(s.label() + " is too long");
                yield new JsonPrimitive(v);
            }
        };
    }

    private static String fmt(double d) {
        return d == Math.rint(d) ? Long.toString((long) d) : Double.toString(d);
    }

    private JsonObject savedSettings(String id) {
        var file = settingsDir.resolve(id + ".json");
        try {
            return Files.exists(file) ? Json.readObject(file) : new JsonObject();
        } catch (IOException e) {
            LOG.warn("Unreadable settings of plugin {}", id, e);
            return new JsonObject();
        }
    }

    /** Defaults from plugin.json, overlaid with what the admin saved. */
    JsonObject effectiveSettings(String id, PluginManifest manifest) {
        var saved = savedSettings(id);
        var json = new JsonObject();
        for (var s : manifest.settings()) {
            var value = saved.has(s.key()) ? saved.get(s.key()) : s.defaultValue();
            if (value == null) value = switch (s.type()) {
                case "boolean" -> new JsonPrimitive(false);
                case "number" -> JsonNull.INSTANCE;
                case "select" -> new JsonPrimitive(s.options().getFirst().value());
                default -> new JsonPrimitive("");
            };
            json.add(s.key(), value);
        }
        return json;
    }

    // --- restarts ---

    /** Stops the manager with the code that makes the entrypoint start it again, after the answer went out. */
    public void restartManager() {
        Thread.ofPlatform().name("plugin-restart").start(() -> {
            try {
                Thread.sleep(500);
            } catch (InterruptedException ignored) {
                // restart anyway
            }
            LOG.info("Restarting ZenithHub for plugin changes");
            System.exit(RESTART_EXIT_CODE);
        });
    }

    /** Restarts running instances that run older plugins, a few seconds apart. */
    public int restartStaleInstances() {
        var key = botKey();
        var stale = runningInstances().stream().filter(id -> !key.equals(supervisor.pluginKey(id))).toList();
        Thread.ofVirtual().name("plugin-instance-restart").start(() -> {
            for (var id : stale) {
                try {
                    supervisor.restart(id);
                    Thread.sleep(3000);
                } catch (Exception e) {
                    LOG.warn("Couldn't restart {} for plugin changes: {}", id, e.getMessage());
                }
            }
        });
        return stale.size();
    }

    // --- helpers ---

    private List<String> runningInstances() {
        var supervisor = this.supervisor;
        var hub = this.hub;
        if (supervisor == null) return List.of();
        var store = hub != null ? hub.store() : null;
        if (store == null) return List.of();
        return store.read(s -> s.instances.stream().map(i -> i.id).filter(supervisor::isRunning).toList());
    }

    private String instanceName(String id) {
        var hub = this.hub;
        if (hub == null) return id;
        return hub.store().read(s -> s.instances.stream().filter(i -> i.id.equals(id)).findFirst().map(i -> i.name).orElse(id));
    }

    private PluginRegistry.Entry entry(String id) {
        return entries.stream().filter(e -> e.id.equals(id)).findFirst().orElse(null);
    }

    private PluginRegistry.Entry requireEntry(String id) {
        var entry = entry(id);
        if (entry == null) throw new com.zenith.manager.web.ApiException(404, "No such plugin");
        return entry;
    }

    /** The context handed to one plugin's manager side. */
    private final class Ctx implements PluginContext {
        private final String id;
        private final PluginManifest manifest;
        private final Path directory;
        private final Logger logger;

        Ctx(String id, PluginManifest manifest, Path directory) {
            this.id = id;
            this.manifest = manifest;
            this.directory = directory;
            this.logger = LoggerFactory.getLogger("Plugin/" + id);
        }

        @Override public String id() { return id; }
        @Override public PluginManifest manifest() { return manifest; }
        @Override public Path directory() { return directory; }
        @Override public Path dataDir() { return dataDir.resolve("plugin-data").resolve(id); }
        @Override public JsonObject settings() { return effectiveSettings(id, manifest); }
        @Override public Logger logger() { return logger; }
        @Override public Hub hub() { return hub; }

        @Override
        public void javalin(Consumer<JavalinConfig> configure) {
            if (!loading) throw new IllegalStateException("javalin() only works during onLoad");
            javalinHooks.add(configure);
        }

        @Override
        public void routes(Consumer<Javalin> register) {
            if (!loading) throw new IllegalStateException("routes() only works during onLoad");
            routeHooks.add(Map.entry(id, register));
        }

        @Override public State.User user(Context ctx) { return hub.web().user(ctx); }
        @Override public State.User requireUser(Context ctx) { return hub.web().requireSignedIn(ctx); }
        @Override public State.User requireAdmin(Context ctx) { return hub.web().requireAdminUser(ctx); }

        @Override
        public State.Instance requireCapability(Context ctx, String instanceId, Capability capability) {
            return hub.web().requireInstanceCapability(ctx, instanceId, capability);
        }

        @Override
        public void onBotEvent(BotEventListener listener) {
            botListeners.computeIfAbsent(id, k -> new CopyOnWriteArrayList<>()).add(listener);
        }

        @Override
        public void publish(String event, String instanceId, JsonElement data) {
            var json = new JsonObject();
            json.addProperty("plugin", id);
            json.addProperty("event", event);
            json.addProperty("ts", System.currentTimeMillis());
            json.add("data", data == null ? JsonNull.INSTANCE : data);
            events.publish("plugin", instanceId, json);
        }

        @Override
        public HttpResponse<String> callBot(String instanceId, String method, String path, JsonElement body) throws Exception {
            return supervisor.pluginRequest(instanceId, id, method, path, null, body == null ? null : Json.COMPACT.toJson(body));
        }
    }

    /** For tests and the admin page: the revision each installed plugin is at. */
    Map<String, String> revisions() {
        var map = new LinkedHashMap<String, String>();
        synchronized (lock) {
            for (var e : entries) map.put(e.id, e.revision);
        }
        return map;
    }

    List<String> enabledIds() {
        var list = new ArrayList<String>();
        synchronized (lock) {
            for (var e : entries) if (e.enabled) list.add(e.id);
        }
        return list;
    }
}
