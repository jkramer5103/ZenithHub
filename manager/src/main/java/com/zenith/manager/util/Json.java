package com.zenith.manager.util;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

public final class Json {
    public static final Gson GSON = new GsonBuilder().disableHtmlEscaping().setPrettyPrinting().create();
    public static final Gson COMPACT = new GsonBuilder().disableHtmlEscaping().serializeNulls().create();

    private Json() {}

    /**
     * Returns a new object: {@code base} with {@code overlay} applied on top.
     * Nested objects merge recursively; any other value (including arrays) replaces.
     */
    public static JsonObject merge(JsonObject base, JsonObject overlay) {
        var result = base.deepCopy();
        mergeInto(result, overlay);
        return result;
    }

    public static void mergeInto(JsonObject target, JsonObject overlay) {
        for (Map.Entry<String, JsonElement> e : overlay.entrySet()) {
            var existing = target.get(e.getKey());
            var value = e.getValue();
            if (existing != null && existing.isJsonObject() && value.isJsonObject()) {
                mergeInto(existing.getAsJsonObject(), value.getAsJsonObject());
            } else {
                target.add(e.getKey(), value.deepCopy());
            }
        }
    }

    /**
     * The minimal overlay that turns {@code base} into {@code actual}, i.e. {@code merge(base, diff) == actual}
     * for every key present in {@code actual}. Keys missing from {@code actual} are ignored.
     */
    public static JsonObject diff(JsonObject base, JsonObject actual) {
        var result = new JsonObject();
        for (Map.Entry<String, JsonElement> e : actual.entrySet()) {
            var baseValue = base.get(e.getKey());
            var value = e.getValue();
            if (baseValue != null && baseValue.isJsonObject() && value.isJsonObject()) {
                var nested = diff(baseValue.getAsJsonObject(), value.getAsJsonObject());
                if (!nested.isEmpty()) result.add(e.getKey(), nested);
            } else if (!value.equals(baseValue)) {
                result.add(e.getKey(), value.deepCopy());
            }
        }
        return result;
    }

    /** Sets a dotted path like {@code server.bind.port}, creating intermediate objects. */
    public static void set(JsonObject root, String path, JsonElement value) {
        var parts = path.split("\\.");
        var obj = root;
        for (int i = 0; i < parts.length - 1; i++) {
            var next = obj.get(parts[i]);
            if (next == null || !next.isJsonObject()) {
                next = new JsonObject();
                obj.add(parts[i], next);
            }
            obj = next.getAsJsonObject();
        }
        obj.add(parts[parts.length - 1], value);
    }

    /** Value at a dotted path, or null. */
    public static JsonElement get(JsonObject root, String path) {
        JsonElement cur = root;
        for (var part : path.split("\\.")) {
            if (cur == null || !cur.isJsonObject()) return null;
            cur = cur.getAsJsonObject().get(part);
        }
        return cur;
    }

    /** Removes a dotted path and prunes objects left empty. */
    public static void remove(JsonObject root, String path) {
        removeParts(root, path.split("\\."), 0);
    }

    private static void removeParts(JsonObject obj, String[] parts, int i) {
        if (i == parts.length - 1) {
            obj.remove(parts[i]);
            return;
        }
        var next = obj.get(parts[i]);
        if (next == null || !next.isJsonObject()) return;
        removeParts(next.getAsJsonObject(), parts, i + 1);
        if (next.getAsJsonObject().isEmpty()) obj.remove(parts[i]);
    }

    public static JsonObject readObject(Path path) throws IOException {
        return JsonParser.parseString(Files.readString(path)).getAsJsonObject();
    }
}
