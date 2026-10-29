package com.zenith.manager.instances;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import com.zenith.manager.ManagerConfig;
import com.zenith.manager.state.State;
import com.zenith.manager.state.StateStore;
import com.zenith.manager.util.Json;
import com.zenith.manager.util.SecureFiles;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;

/**
 * Zenith settings at the three layers the UI edits: global, server and instance.
 *
 * Settings are addressed by dotted paths from Zenith's config schema. Changes to running
 * instances are applied live, without a restart.
 */
public final class SettingsService {
    private static final Logger LOG = LoggerFactory.getLogger("Settings");
    /** Managed by the manager itself or meaningless in managed mode. */
    private static final List<String> HIDDEN_PREFIXES = List.of(
        "authentication.", "discord.", "server.bind.", "client.server.",
        "database.", "plugins."
    );
    private static final Set<String> HIDDEN = Set.of("warning", "server.upnp", "server.proxyIP", "debug.lockFile");
    /** What Zenith reports to Discord and when it pings; the manager's bot posts it. */
    private static final List<String> DISCORD_VISIBLE_PREFIXES = List.of(
        "discord.mentionRoleOn", "discord.mentionOn", "discord.reportCoords", "discord.clientConnectionMessages", "discord.mcVersionMismatchWarning", "discord.notifications."
    );
    private static final Set<String> EDITABLE_TYPES = Set.of("boolean", "integer", "number", "string", "enum", "player", "list");

    public enum Scope { GLOBAL, SERVER, INSTANCE }

    private final ManagerConfig config;
    private final StateStore store;
    private final Supervisor supervisor;
    private volatile JsonObject schema;

    public SettingsService(ManagerConfig config, StateStore store, Supervisor supervisor) {
        this.config = config;
        this.store = store;
        this.supervisor = supervisor;
    }

    /**
     * Asks the Zenith binary for its config defaults and schema, so settings pages work
     * before any instance has run and stay current after image updates.
     */
    public void refreshSchema() {
        var dir = config.dataDir().resolve("manager").resolve("schema-dump");
        try {
            SecureFiles.deleteRecursively(dir);
            Files.createDirectories(dir);
            var pb = new ProcessBuilder(config.zenithCommand())
                .directory(dir.toFile())
                .redirectErrorStream(true)
                .redirectOutput(ProcessBuilder.Redirect.DISCARD);
            pb.environment().put("ZENITH_DUMP_SCHEMA", "1");
            pb.environment().remove("ZENITH_MANAGED");
            var process = pb.start();
            if (!process.waitFor(2, TimeUnit.MINUTES)) {
                process.destroyForcibly();
                throw new IOException("timed out");
            }
            var defaults = dir.resolve("config.defaults.json");
            var schemaFile = dir.resolve("config.schema.json");
            if (process.exitValue() != 0 || !Files.exists(defaults) || !Files.exists(schemaFile)) {
                throw new IOException("exit code " + process.exitValue());
            }
            SecureFiles.copy(defaults, config.zenithDefaultsFile());
            SecureFiles.copy(schemaFile, config.zenithSchemaFile());
            schema = null;
            LOG.info("Loaded Zenith settings schema");
        } catch (Exception e) {
            LOG.warn("Couldn't read the Zenith settings schema: {}", e.getMessage());
        } finally {
            try {
                SecureFiles.deleteRecursively(dir);
            } catch (IOException ignored) { }
        }
    }

    /** Editable settings: path → type descriptor. */
    public JsonObject schema() {
        var cached = schema;
        if (cached != null) return cached;
        try {
            var full = Json.readObject(config.zenithSchemaFile());
            var visible = new JsonObject();
            for (var e : full.entrySet()) {
                if (editable(e.getValue().getAsJsonObject()) && !hidden(e.getKey())) visible.add(e.getKey(), e.getValue());
            }
            schema = visible;
            return visible;
        } catch (IOException e) {
            throw new IllegalStateException("Settings aren't available yet. Try again in a moment.");
        }
    }

    private static boolean editable(JsonObject type) {
        var name = type.get("type").getAsString();
        if (name.equals("list")) {
            var items = type.getAsJsonObject("items");
            return items != null && editable(items);
        }
        if (name.equals("object")) {
            var fields = type.getAsJsonObject("fields");
            if (fields == null || fields.isEmpty()) return false;
            for (var f : fields.entrySet()) {
                var t = f.getValue().getAsJsonObject().get("type").getAsString();
                // flat records only
                if (t.equals("list") || t.equals("object") || !editable(f.getValue().getAsJsonObject())) return false;
            }
            return true;
        }
        return EDITABLE_TYPES.contains(name);
    }

    private static boolean hidden(String path) {
        if (path.startsWith("discord.") && DISCORD_VISIBLE_PREFIXES.stream().anyMatch(path::startsWith)) return false;
        return HIDDEN.contains(path) || HIDDEN_PREFIXES.stream().anyMatch(path::startsWith);
    }

    private JsonObject defaults() {
        try {
            return Json.readObject(config.zenithDefaultsFile());
        } catch (IOException e) {
            throw new IllegalStateException("Settings aren't available yet. Try again in a moment.");
        }
    }

    // --- reading ---

    /**
     * Everything a settings page needs: values in effect, what this layer inherits, this layer's own overrides,
     * which layer each value comes from, and the values the manager controls.
     */
    public synchronized JsonObject view(Scope scope, String id) throws Exception {
        var schema = schema();
        var defaults = defaults();
        var layers = new LinkedHashMap<String, JsonObject>();
        layers.put("default", defaults);
        JsonObject own;
        Map<String, JsonElement> forced = Map.of();
        boolean live = false;
        JsonObject effective;
        switch (scope) {
            case GLOBAL -> {
                own = store.read(s -> s.globalSettings.deepCopy());
                effective = Json.merge(defaults, own);
            }
            case SERVER -> {
                var layer = store.read(s -> {
                    var server = findServer(s, id);
                    return new JsonObject[] { s.globalSettings.deepCopy(), server.settings.deepCopy() };
                });
                layers.put("global", layer[0]);
                own = layer[1];
                effective = Json.merge(Json.merge(defaults, layer[0]), own);
            }
            case INSTANCE -> {
                var ctx = supervisor.context(id);
                layers.put("global", ctx.globalSettings());
                layers.put("server", ctx.server().settings);
                forced = ConfigComposer.forcedValues(ctx);
                var liveConfig = supervisor.isRunning(id) ? tryLiveConfig(id) : null;
                if (liveConfig != null) {
                    own = ConfigComposer.extractInstanceOverrides(defaults, ctx, liveConfig);
                    effective = liveConfig;
                    live = true;
                } else {
                    own = ctx.instance().settings.deepCopy();
                    effective = ConfigComposer.compose(defaults, ctx);
                }
            }
            default -> throw new IllegalArgumentException();
        }

        var inherited = new JsonObject();
        layers.values().forEach(l -> Json.mergeInto(inherited, l));
        var ownLayerName = scope.name().toLowerCase();

        var values = new JsonObject();
        var inheritedValues = new JsonObject();
        var overrides = new JsonObject();
        var sources = new JsonObject();
        for (var path : schema.keySet()) {
            values.add(path, orNull(Json.get(effective, path)));
            inheritedValues.add(path, orNull(Json.get(inherited, path)));
            var override = Json.get(own, path);
            if (forced.containsKey(path)) {
                sources.addProperty(path, "forced");
            } else if (override != null) {
                overrides.add(path, override);
                sources.addProperty(path, ownLayerName);
            } else {
                sources.addProperty(path, sourceOf(layers, path));
            }
        }
        var forcedPaths = new JsonArray();
        forced.keySet().stream().filter(schema::has).forEach(forcedPaths::add);

        var json = new JsonObject();
        json.addProperty("scope", ownLayerName);
        json.addProperty("live", live);
        json.add("schema", schema);
        json.add("values", values);
        json.add("inherited", inheritedValues);
        json.add("overrides", overrides);
        json.add("sources", sources);
        json.add("forced", forcedPaths);
        return json;
    }

    private static String sourceOf(LinkedHashMap<String, JsonObject> layers, String path) {
        String source = "default";
        for (var e : layers.entrySet()) {
            if (Json.get(e.getValue(), path) != null) source = e.getKey();
        }
        return source;
    }

    // --- writing ---

    /**
     * Applies {@code set} (path → value) and {@code reset} (paths back to inherited) to one layer,
     * and pushes the resulting values to every affected running instance.
     */
    public synchronized void update(Scope scope, String id, JsonObject set, List<String> reset) throws Exception {
        var schema = schema();
        var defaults = defaults();
        var changed = new ArrayList<String>();
        for (var e : set.entrySet()) {
            validate(schema, e.getKey(), e.getValue());
            changed.add(e.getKey());
        }
        for (var path : reset) {
            if (!schema.has(path)) throw new IllegalArgumentException("Unknown setting: " + path);
            changed.add(path);
        }
        if (changed.isEmpty()) return;

        // instances whose effective values may change
        List<String> affected = store.read(s -> switch (scope) {
            case GLOBAL -> s.instances.stream().map(i -> i.id).toList();
            case SERVER -> {
                findServer(s, id);
                yield s.instances.stream().filter(i -> i.serverId.equals(id)).map(i -> i.id).toList();
            }
            case INSTANCE -> {
                if (s.instances.stream().noneMatch(i -> i.id.equals(id))) throw new IllegalArgumentException("Unknown instance");
                yield List.of(id);
            }
        });
        // running instances that answer; the rest pick up the change on their next start
        var liveConfigs = new LinkedHashMap<String, JsonObject>();
        for (var instanceId : affected) {
            if (!supervisor.isRunning(instanceId)) continue;
            var live = tryLiveConfig(instanceId);
            if (live != null) liveConfigs.put(instanceId, live);
        }
        var running = List.copyOf(liveConfigs.keySet());

        if (scope == Scope.INSTANCE) {
            var forced = ConfigComposer.forcedValues(supervisor.context(id));
            for (var path : changed) {
                if (forced.containsKey(path)) throw new IllegalArgumentException(path + " is managed automatically");
            }
        }

        // capture in-game changes of running instances against the current layers first,
        // so they aren't mistaken for overrides once the layers below change
        for (var instanceId : running) {
            var ctx = supervisor.context(instanceId);
            var overrides = ConfigComposer.extractInstanceOverrides(defaults, ctx, liveConfigs.get(instanceId));
            store.update(s -> s.instances.stream().filter(i -> i.id.equals(instanceId)).forEach(i -> i.settings = overrides));
        }

        store.update(s -> {
            JsonObject layer = switch (scope) {
                case GLOBAL -> s.globalSettings;
                case SERVER -> findServer(s, id).settings;
                case INSTANCE -> s.instances.stream().filter(i -> i.id.equals(id)).findFirst().orElseThrow().settings;
            };
            set.entrySet().forEach(e -> Json.set(layer, e.getKey(), e.getValue().deepCopy()));
            reset.forEach(path -> Json.remove(layer, path));
        });

        for (var instanceId : running) {
            var composed = ConfigComposer.compose(defaults, supervisor.context(instanceId));
            var patch = new JsonObject();
            for (var path : changed) {
                var value = Json.get(composed, path);
                if (value != null) Json.set(patch, path, value);
            }
            try {
                supervisor.pushConfig(instanceId, patch);
            } catch (Exception e) {
                // the change is saved and applies on the next start
                LOG.warn("Couldn't apply settings to running instance {}: {}", instanceId, e.getMessage());
                if (scope == Scope.INSTANCE) throw new IllegalStateException("Saved, but the running instance rejected it: " + e.getMessage());
            }
        }
    }

    private JsonObject tryLiveConfig(String instanceId) {
        try {
            return supervisor.liveConfig(instanceId);
        } catch (Exception e) {
            LOG.warn("Couldn't read the live settings of instance {}: {}", instanceId, e.getMessage());
            return null;
        }
    }

    private static void validate(JsonObject schema, String path, JsonElement value) {
        if (!schema.has(path)) throw new IllegalArgumentException("Unknown setting: " + path);
        var type = schema.getAsJsonObject(path);
        if (!matches(type, value)) throw new IllegalArgumentException("Invalid value for " + path);
    }

    private static boolean matches(JsonObject type, JsonElement value) {
        if (value == null || value.isJsonNull()) return false;
        return switch (type.get("type").getAsString()) {
            case "boolean" -> value.isJsonPrimitive() && value.getAsJsonPrimitive().isBoolean();
            case "integer" -> value.isJsonPrimitive() && value.getAsJsonPrimitive().isNumber()
                && value.getAsDouble() == Math.rint(value.getAsDouble()) && Math.abs(value.getAsDouble()) <= Long.MAX_VALUE;
            case "number" -> value.isJsonPrimitive() && value.getAsJsonPrimitive().isNumber() && Double.isFinite(value.getAsDouble());
            case "string" -> value.isJsonPrimitive() && value.getAsJsonPrimitive().isString() && value.getAsString().length() <= 10_000;
            case "enum" -> value.isJsonPrimitive() && value.getAsJsonPrimitive().isString()
                && type.getAsJsonArray("values").contains(value);
            // Zenith's PlayerEntry
            case "player" -> {
                if (!value.isJsonObject()) yield false;
                var o = value.getAsJsonObject();
                yield o.has("username") && o.get("username").isJsonPrimitive() && o.get("username").getAsString().matches("^[A-Za-z0-9_.]{1,16}$")
                    && o.has("uuid") && o.get("uuid").isJsonPrimitive()
                    && o.get("uuid").getAsString().matches("^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$")
                    && (!o.has("lastRefreshed") || o.get("lastRefreshed").isJsonPrimitive() && o.get("lastRefreshed").getAsJsonPrimitive().isNumber());
            }
            case "list" -> {
                if (!value.isJsonArray() || value.getAsJsonArray().size() > 10_000) yield false;
                var items = type.getAsJsonObject("items");
                if (items == null) yield true;
                for (var item : value.getAsJsonArray()) if (!matches(items, item)) yield false;
                yield true;
            }
            case "object" -> {
                if (!value.isJsonObject()) yield false;
                var o = value.getAsJsonObject();
                var fields = type.getAsJsonObject("fields");
                if (!fields.keySet().containsAll(o.keySet()) || !o.keySet().containsAll(fields.keySet())) yield false;
                for (var f : fields.entrySet()) if (!matches(f.getValue().getAsJsonObject(), o.get(f.getKey()))) yield false;
                yield true;
            }
            default -> false;
        };
    }

    private static JsonElement orNull(JsonElement e) {
        return e == null ? JsonNull.INSTANCE : e;
    }

    private static State.ServerProfile findServer(State s, String id) {
        return s.servers.stream().filter(x -> x.id.equals(id)).findFirst()
            .orElseThrow(() -> new IllegalArgumentException("Unknown server"));
    }
}
