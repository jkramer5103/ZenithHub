package com.zenith.manager.boot;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * A plugin's {@code plugin.json}, checked. Paths are relative to the plugin's folder.
 * See docs/plugins.md for the format.
 */
public record PluginManifest(
    String id,
    String name,
    String version,
    String description,
    List<String> authors,
    String url,
    Part manager,
    Part bot,
    Web web,
    List<Setting> settings
) {
    public static final String FILE = "plugin.json";
    public static final Pattern ID = Pattern.compile("^[a-z0-9][a-z0-9_-]{0,39}$");
    private static final Pattern KEY = Pattern.compile("^[A-Za-z][A-Za-z0-9_]{0,39}$");
    private static final Pattern CLASS = Pattern.compile("^[A-Za-z_$][A-Za-z0-9_$]*(\\.[A-Za-z_$][A-Za-z0-9_$]*)*$");
    private static final Set<String> TYPES = Set.of("boolean", "number", "string", "text", "select", "secret");

    /** Java code for the manager or for each bot: its jars, the class to start, and its mixins. */
    public record Part(List<String> jars, String main, List<String> mixins) {
        public boolean present() {
            return !jars.isEmpty();
        }
    }

    /** Scripts and stylesheets every signed-in browser loads. */
    public record Web(List<String> scripts, List<String> styles) {
        public boolean present() {
            return !scripts.isEmpty() || !styles.isEmpty();
        }
    }

    /** One field on the plugin's settings form. {@code options} are for select fields. */
    public record Setting(String key, String label, String type, String description, JsonElement defaultValue,
                          List<Option> options, Double min, Double max, boolean isPublic) {}

    public record Option(String value, String label) {}

    public static PluginManifest read(Path dir) throws IOException {
        var file = dir.resolve(FILE);
        if (!Files.isRegularFile(file)) throw new IllegalArgumentException("The zip has no plugin.json");
        JsonObject json;
        try {
            json = JsonParser.parseString(Files.readString(file)).getAsJsonObject();
        } catch (Exception e) {
            throw new IllegalArgumentException("plugin.json isn't valid JSON: " + e.getMessage());
        }
        var id = str(json, "id", true, 40);
        if (!ID.matcher(id).matches()) throw new IllegalArgumentException("The plugin id must be 1 to 40 lowercase letters, digits, - or _");
        var name = str(json, "name", true, 60);
        var version = str(json, "version", true, 40);
        var description = str(json, "description", false, 1000);
        var url = str(json, "url", false, 300);
        if (!url.isEmpty() && !url.matches("^https?://\\S+$")) throw new IllegalArgumentException("url must be an http(s) link");
        var authors = new ArrayList<String>();
        if (json.has("authors")) for (var a : array(json, "authors")) authors.add(limit(a.getAsString(), 60, "authors"));
        var manager = part(dir, json, "manager");
        var bot = part(dir, json, "bot");
        var web = web(dir, json);
        var settings = settings(json);
        if (!manager.present() && !bot.present() && !web.present()) {
            throw new IllegalArgumentException("The plugin has nothing to run: no manager/ or bot/ jars and no web/ files");
        }
        return new PluginManifest(id, name, version, description, List.copyOf(authors), url, manager, bot, web, settings);
    }

    private static Part part(Path dir, JsonObject json, String key) throws IOException {
        var spec = json.has(key) && json.get(key).isJsonObject() ? json.getAsJsonObject(key) : new JsonObject();
        var jars = new ArrayList<String>();
        if (spec.has("jars")) {
            for (var j : array(spec, "jars")) jars.add(existing(dir, j.getAsString(), key + ".jars"));
        } else if (Files.isDirectory(dir.resolve(key))) {
            // every jar in manager/ or bot/
            try (Stream<Path> files = Files.walk(dir.resolve(key))) {
                files.filter(p -> Files.isRegularFile(p) && p.toString().endsWith(".jar")).sorted()
                    .forEach(p -> jars.add(dir.relativize(p).toString().replace('\\', '/')));
            }
        }
        var main = str(spec, "main", false, 200);
        if (!main.isEmpty() && !CLASS.matcher(main).matches()) throw new IllegalArgumentException(key + ".main isn't a class name");
        var mixins = new ArrayList<String>();
        if (spec.has("mixins")) {
            for (var m : array(spec, "mixins")) {
                var name = m.getAsString();
                if (!CLASS.matcher(name).matches()) throw new IllegalArgumentException(key + ".mixins: " + name + " isn't a class name");
                mixins.add(name);
            }
        }
        if (jars.isEmpty() && (!main.isEmpty() || !mixins.isEmpty())) {
            throw new IllegalArgumentException(key + " names classes but the plugin has no " + key + "/ jars");
        }
        return new Part(List.copyOf(jars), main.isEmpty() ? null : main, List.copyOf(mixins));
    }

    private static Web web(Path dir, JsonObject json) {
        var spec = json.has("web") && json.get("web").isJsonObject() ? json.getAsJsonObject("web") : null;
        var scripts = new ArrayList<String>();
        var styles = new ArrayList<String>();
        // each list defaults to web/index.js or web/index.css unless plugin.json gives it
        if (spec != null && spec.has("scripts")) for (var s : array(spec, "scripts")) scripts.add(webFile(dir, s.getAsString(), "web.scripts"));
        else if (Files.isRegularFile(dir.resolve("web/index.js"))) scripts.add("web/index.js");
        if (spec != null && spec.has("styles")) for (var s : array(spec, "styles")) styles.add(webFile(dir, s.getAsString(), "web.styles"));
        else if (Files.isRegularFile(dir.resolve("web/index.css"))) styles.add("web/index.css");
        return new Web(List.copyOf(scripts), List.copyOf(styles));
    }

    private static String webFile(Path dir, String path, String field) {
        var file = existing(dir, path, field);
        if (!file.startsWith("web/")) throw new IllegalArgumentException(field + ": " + path + " must be in web/");
        return file;
    }

    private static List<Setting> settings(JsonObject json) {
        var list = new ArrayList<Setting>();
        if (!json.has("settings")) return List.of();
        var keys = new java.util.HashSet<String>();
        for (var element : array(json, "settings")) {
            if (!element.isJsonObject()) throw new IllegalArgumentException("Each setting must be an object");
            var s = element.getAsJsonObject();
            var key = str(s, "key", true, 40);
            if (!KEY.matcher(key).matches()) throw new IllegalArgumentException("Setting key " + key + " must start with a letter and use letters, digits or _");
            if (!keys.add(key)) throw new IllegalArgumentException("Setting " + key + " is listed twice");
            var type = s.has("type") ? s.get("type").getAsString() : "string";
            if (!TYPES.contains(type)) throw new IllegalArgumentException("Setting " + key + " has an unknown type " + type);
            var options = new ArrayList<Option>();
            if (s.has("options")) {
                for (var o : array(s, "options")) {
                    if (o.isJsonObject()) options.add(new Option(o.getAsJsonObject().get("value").getAsString(),
                        o.getAsJsonObject().has("label") ? o.getAsJsonObject().get("label").getAsString() : o.getAsJsonObject().get("value").getAsString()));
                    else options.add(new Option(o.getAsString(), o.getAsString()));
                }
            }
            if (type.equals("select") && options.isEmpty()) throw new IllegalArgumentException("Setting " + key + " needs options");
            var label = s.has("label") ? limit(s.get("label").getAsString(), 80, "label") : key;
            list.add(new Setting(key, label, type, str(s, "description", false, 500), s.has("default") ? s.get("default") : null,
                List.copyOf(options), num(s, "min"), num(s, "max"), s.has("public") && s.get("public").getAsBoolean() && !type.equals("secret")));
        }
        return List.copyOf(list);
    }

    /** A path inside the plugin's folder that exists. */
    private static String existing(Path dir, String path, String field) {
        var normalized = path.replace('\\', '/').replaceAll("^\\./", "");
        var resolved = dir.resolve(normalized).normalize();
        if (normalized.startsWith("/") || !resolved.startsWith(dir.normalize())) throw new IllegalArgumentException(field + ": " + path + " is outside the plugin");
        if (!Files.isRegularFile(resolved)) throw new IllegalArgumentException(field + ": " + path + " isn't in the zip");
        return dir.normalize().relativize(resolved).toString().replace('\\', '/');
    }

    private static JsonArray array(JsonObject json, String key) {
        if (!json.get(key).isJsonArray()) throw new IllegalArgumentException(key + " must be a list");
        return json.getAsJsonArray(key);
    }

    private static Double num(JsonObject json, String key) {
        return json.has(key) && json.get(key).isJsonPrimitive() ? json.get(key).getAsDouble() : null;
    }

    private static String str(JsonObject json, String key, boolean required, int max) {
        var value = json.has(key) && json.get(key).isJsonPrimitive() ? json.get(key).getAsString().strip() : "";
        if (required && value.isEmpty()) throw new IllegalArgumentException("plugin.json needs \"" + key + "\"");
        return limit(value, max, key);
    }

    private static String limit(String value, int max, String key) {
        if (value.length() > max) throw new IllegalArgumentException(key + " is longer than " + max + " characters");
        return value;
    }
}
