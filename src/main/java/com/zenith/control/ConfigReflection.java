package com.zenith.control;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.zenith.feature.whitelist.PlayerEntry;
import it.unimi.dsi.fastutil.ints.IntCollection;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;

import static com.zenith.Globals.GSON;

/**
 * Describes and edits {@link com.zenith.util.config.Config} by reflection, so the manager
 * can render a settings UI and apply changes to a running instance without commands.
 */
public final class ConfigReflection {
    private ConfigReflection() {}

    /**
     * Flat map of dotted setting path to type descriptor, for every leaf setting:
     * {"type": "boolean|integer|number|string|enum|player|list|object|map|other", "values": [...enum constants],
     * "items": {...list item type}, "fields": {name: {...} for records}}
     */
    public static JsonObject schema(Object config) {
        var out = new JsonObject();
        describeFields(config.getClass(), "", out);
        return out;
    }

    private static void describeFields(Class<?> clazz, String prefix, JsonObject out) {
        for (var field : fields(clazz)) {
            var path = prefix + field.getName();
            if (isConfigObject(field.getType())) {
                describeFields(field.getType(), path + ".", out);
            } else {
                out.add(path, describe(field.getGenericType()));
            }
        }
    }

    private static JsonObject describe(Type type) {
        var json = new JsonObject();
        var raw = rawClass(type);
        if (raw == boolean.class || raw == Boolean.class) {
            json.addProperty("type", "boolean");
        } else if (raw == int.class || raw == long.class || raw == short.class || raw == byte.class
            || raw == Integer.class || raw == Long.class || raw == Short.class || raw == Byte.class) {
            json.addProperty("type", "integer");
        } else if (raw == float.class || raw == double.class || raw == Float.class || raw == Double.class) {
            json.addProperty("type", "number");
        } else if (raw == String.class) {
            json.addProperty("type", "string");
        } else if (raw != null && raw.isEnum()) {
            json.addProperty("type", "enum");
            var values = new JsonArray();
            for (var constant : raw.getEnumConstants()) values.add(((Enum<?>) constant).name());
            json.add("values", values);
        } else if (raw == PlayerEntry.class) {
            json.addProperty("type", "player");
        } else if (raw != null && Collection.class.isAssignableFrom(raw)) {
            json.addProperty("type", "list");
            if (type instanceof ParameterizedType p && p.getActualTypeArguments().length == 1) {
                json.add("items", describe(p.getActualTypeArguments()[0]));
            } else if (IntCollection.class.isAssignableFrom(raw)) {
                json.add("items", describe(int.class));
            }
        } else if (raw != null && Map.class.isAssignableFrom(raw)) {
            json.addProperty("type", "map");
        } else if (raw != null && raw.isRecord()) {
            // small value types like waypoints: edited as a whole, field by field
            json.addProperty("type", "object");
            var fields = new JsonObject();
            for (var field : raw.getDeclaredFields()) {
                if (Modifier.isStatic(field.getModifiers()) || field.isSynthetic()) continue;
                fields.add(field.getName(), describe(field.getGenericType()));
            }
            json.add("fields", fields);
        } else {
            json.addProperty("type", "other");
        }
        return json;
    }

    /**
     * Merges a nested JSON patch into the live config object.
     * Validates every value before changing anything, so a bad patch leaves the config untouched.
     */
    public static void apply(Object config, JsonObject patch) {
        if (patch == null) throw new IllegalArgumentException("Missing settings");
        var changes = new ArrayList<Runnable>();
        collect(config, patch, "", changes);
        changes.forEach(Runnable::run);
    }

    private static void collect(Object target, JsonObject patch, String prefix, List<Runnable> changes) {
        for (var entry : patch.entrySet()) {
            var path = prefix + entry.getKey();
            var field = findField(target.getClass(), entry.getKey());
            if (field == null) throw new IllegalArgumentException("Unknown setting: " + path);
            var value = entry.getValue();
            try {
                if (isConfigObject(field.getType())) {
                    if (!value.isJsonObject()) throw new IllegalArgumentException(path + " is a group of settings, not a value");
                    collect(field.get(target), value.getAsJsonObject(), path + ".", changes);
                    continue;
                }
                changes.add(setter(target, field, parse(path, field, value)));
            } catch (IllegalAccessException e) {
                throw new IllegalArgumentException("Can't change " + path);
            }
        }
    }

    private static Object parse(String path, Field field, JsonElement value) {
        Object parsed;
        try {
            parsed = GSON.fromJson(value, field.getGenericType());
        } catch (Exception e) {
            throw new IllegalArgumentException("Invalid value for " + path);
        }
        // Gson turns unknown enum names into null
        if (parsed == null && !value.isJsonNull()) throw new IllegalArgumentException("Invalid value for " + path);
        if (parsed == null && field.getType().isPrimitive()) throw new IllegalArgumentException(path + " can't be empty");
        return parsed;
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private static Runnable setter(Object target, Field field, Object value) throws IllegalAccessException {
        // collections are updated in place: features keep references to them (e.g. player lists)
        var current = field.get(target);
        if (current instanceof Collection c && value instanceof Collection v) {
            return () -> {
                c.clear();
                c.addAll(v);
            };
        }
        if (current instanceof Map m && value instanceof Map v) {
            return () -> {
                m.clear();
                m.putAll(v);
            };
        }
        if (!Modifier.isFinal(field.getModifiers())) {
            return () -> {
                try {
                    field.set(target, value);
                } catch (IllegalAccessException e) {
                    throw new IllegalStateException(e);
                }
            };
        }
        throw new IllegalArgumentException(field.getName() + " can't be changed");
    }

    private static List<Field> fields(Class<?> clazz) {
        var result = new ArrayList<Field>();
        // declared fields: native image metadata registers allDeclaredFields for config classes
        for (var field : clazz.getDeclaredFields()) {
            int mods = field.getModifiers();
            if (!Modifier.isPublic(mods) || Modifier.isStatic(mods) || Modifier.isTransient(mods) || field.isSynthetic()) continue;
            result.add(field);
        }
        return result;
    }

    private static Field findField(Class<?> clazz, String name) {
        for (var field : fields(clazz)) {
            if (field.getName().equals(name)) return field;
        }
        return null;
    }

    /** Nested settings groups are Zenith's own (non-enum, non-collection) classes. */
    private static boolean isConfigObject(Class<?> type) {
        return type.getName().startsWith("com.zenith.") && !type.isEnum() && !type.isInterface()
            && !Collection.class.isAssignableFrom(type) && !Map.class.isAssignableFrom(type);
    }

    private static Class<?> rawClass(Type type) {
        if (type instanceof Class<?> c) return c;
        if (type instanceof ParameterizedType p && p.getRawType() instanceof Class<?> c) return c;
        return null;
    }
}
