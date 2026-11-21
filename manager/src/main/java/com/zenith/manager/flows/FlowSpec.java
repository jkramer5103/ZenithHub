package com.zenith.manager.flows;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.zenith.manager.auth.Capability;
import com.zenith.manager.instances.Actions;
import org.jspecify.annotations.Nullable;

import java.time.DateTimeException;
import java.time.ZoneId;
import java.util.EnumSet;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Checks a flow from the web UI and copies only the fields it knows, so nothing unexpected is stored or run.
 *
 * <pre>
 * {id, name, enabled, cooldown, mode, trigger: {kind, …}, steps: [step…]}
 * step: {id, type, …}; "if" has cond, then, else; "repeat", "forever" and "repeatUntil" have steps
 * </pre>
 */
public final class FlowSpec {
    static final Pattern ID = Pattern.compile("[A-Za-z0-9_-]{1,40}");
    static final Pattern PLAYER = Pattern.compile("[A-Za-z0-9_]{1,16}");
    static final Pattern VAR = Pattern.compile("[a-z][A-Za-z0-9_]{0,23}");
    static final Pattern SIGNAL = Pattern.compile("[A-Za-z0-9 _-]{1,32}");
    private static final Pattern TIME = Pattern.compile("([01]\\d|2[0-3]):[0-5]\\d");
    private static final Pattern RESOURCE = Pattern.compile("(minecraft:)?[a-z0-9_]{1,64}");
    static final int MAX_STEPS = 200;
    static final int MAX_DEPTH = 8;
    static final int MAX_WAIT = 3600;

    /** What a flow can start on. */
    public static final Set<String> TRIGGERS = Set.of("manual", "every", "daily", "chat", "whisper", "player", "playerLeft", "attacked", "totem",
        "noTotems", "health", "food", "death", "joined", "left", "crashed", "queueComplete", "queueBelow", "serverRestarting", "controlled", "afk",
        "receive");
    /** What "wait until" can wait for: the events among the triggers. */
    static final Set<String> WAITABLE = Set.of("chat", "whisper", "player", "playerLeft", "attacked", "totem", "health", "food", "death", "joined",
        "left", "queueComplete", "queueBelow", "controlled", "afk", "receive");
    static final Set<String> CONDITIONS = Set.of("health", "food", "playing", "online", "inQueue", "inAfkZone", "earning", "text", "friend",
        "time", "chance", "var", "near", "dimension", "hasItem");
    /** Values every message can use, so they can't be variable names. */
    static final Set<String> RESERVED = Set.of("player", "message", "health", "food", "x", "y", "z", "time", "date", "server", "dimension",
        "totemsLeft", "position", "previous", "reason");
    /** Actions with blocks of their own, or that would let a flow change tasks or type commands. */
    private static final Set<String> NOT_AS_ACTION = Set.of("command", "addTask", "deleteTask", "notify");

    private FlowSpec() {}

    /** A mistake in one block, so the editor can point at it. */
    public static final class Invalid extends IllegalArgumentException {
        public final @Nullable String step;

        Invalid(@Nullable String step, String message) {
            super(message);
            this.step = step;
        }
    }

    /** The checked copy, plus what it needs to run. */
    public record Checked(JsonObject flow, Set<Capability> needs) {}

    public static Checked check(JsonObject in) {
        var out = new JsonObject();
        var needs = EnumSet.of(Capability.CONTROL);
        out.addProperty("id", match(in, "id", ID));
        var name = string(in, "name").strip();
        if (name.isEmpty() || name.length() > 60) throw new IllegalArgumentException("Give the flow a name of up to 60 characters");
        out.addProperty("name", name);
        out.addProperty("enabled", bool(in, "enabled"));
        out.addProperty("cooldown", in.has("cooldown") ? range(in, "cooldown", 0, 86_400) : 10);
        var mode = in.has("mode") ? string(in, "mode") : "ignore";
        if (!mode.equals("ignore") && !mode.equals("restart")) throw new IllegalArgumentException("Unknown mode " + mode);
        out.addProperty("mode", mode);
        try {
            out.add("trigger", trigger(obj(in, "trigger"), false));
        } catch (Invalid e) {
            throw e;
        } catch (IllegalArgumentException e) {
            throw new Invalid("trigger", e.getMessage());
        }
        int[] count = {0};
        out.add("steps", steps(arr(in, "steps"), needs, count, 0));
        return new Checked(out, needs);
    }

    static JsonObject trigger(JsonObject in, boolean waiting) {
        var kind = string(in, "kind");
        if (!(waiting ? WAITABLE : TRIGGERS).contains(kind)) throw new IllegalArgumentException("Unknown trigger " + kind);
        var out = new JsonObject();
        out.addProperty("kind", kind);
        switch (kind) {
            case "chat", "whisper" -> {
                out.addProperty("contains", optText(in, "contains", 100));
                out.addProperty("from", optPlayer(in, "from"));
            }
            case "player", "playerLeft" -> {
                var who = optString(in, "who");
                if (who.isEmpty()) who = "anyone";
                if (!who.equals("anyone") && !who.equals("stranger") && !PLAYER.matcher(who).matches()) throw new IllegalArgumentException("Not a player name: " + who);
                out.addProperty("who", who);
            }
            case "health", "food" -> out.addProperty("below", range(in, "below", 1, 19));
            case "every" -> out.addProperty("minutes", range(in, "minutes", 1, 1440));
            case "daily" -> {
                out.addProperty("at", match(in, "at", TIME));
                var days = optString(in, "days");
                if (!days.matches("[1-7]{0,7}")) throw new IllegalArgumentException("Invalid days");
                out.addProperty("days", days.isEmpty() ? "1234567" : days);
                out.addProperty("zone", zone(in));
            }
            case "left" -> out.addProperty("reason", optText(in, "reason", 100));
            case "queueBelow" -> out.addProperty("position", range(in, "position", 1, 10_000));
            case "controlled" -> out.addProperty("on", oneOf(in, "on", Set.of("start", "stop")));
            case "afk" -> out.addProperty("on", oneOf(in, "on", Set.of("earningStopped", "earningStarted", "leftZone", "enteredZone")));
            case "receive" -> out.addProperty("name", match(in, "name", SIGNAL));
            default -> {}
        }
        return out;
    }

    private static JsonArray steps(JsonArray in, Set<Capability> needs, int[] count, int depth) {
        if (depth > MAX_DEPTH) throw new IllegalArgumentException("Blocks are nested too deeply");
        var out = new JsonArray();
        for (var element : in) {
            if (++count[0] > MAX_STEPS) throw new IllegalArgumentException("A flow can have at most " + MAX_STEPS + " blocks");
            var step = element.getAsJsonObject();
            try {
                out.add(step(step, needs, count, depth));
            } catch (Invalid e) {
                throw e;
            } catch (IllegalArgumentException e) {
                throw new Invalid(step.has("id") ? step.get("id").getAsString() : null, e.getMessage());
            }
        }
        return out;
    }

    private static JsonObject step(JsonObject in, Set<Capability> needs, int[] count, int depth) {
        var out = new JsonObject();
        out.addProperty("id", match(in, "id", ID));
        var type = string(in, "type");
        out.addProperty("type", type);
        if (bool(in, "off")) out.addProperty("off", true);
        switch (type) {
            case "action" -> {
                var run = obj(in, "run");
                if (NOT_AS_ACTION.contains(string(run, "action"))) throw new IllegalArgumentException("Not allowed in a flow: " + string(run, "action"));
                var command = Actions.parse(run);
                needs.add(command.capability());
                if (command.alsoNeeds() != null) needs.add(command.alsoNeeds());
                out.add("run", run.deepCopy());
            }
            case "say" -> {
                out.addProperty("text", text(in, "text", 256));
                needs.add(Capability.CHAT);
            }
            case "whisper" -> {
                var player = string(in, "player").strip();
                if (player.isEmpty() || player.length() > 40) throw new IllegalArgumentException("Who should get the whisper?");
                if (!PLAYER.matcher(player).matches() && !player.matches("\\{[A-Za-z0-9_]+}")) throw new IllegalArgumentException("Not a player name: " + player);
                out.addProperty("player", player);
                out.addProperty("text", text(in, "text", 200));
                needs.add(Capability.CHAT);
            }
            case "notify" -> out.addProperty("text", text(in, "text", 200));
            case "log" -> out.addProperty("text", text(in, "text", 200));
            case "connect", "disconnect", "stop" -> {}
            case "wait" -> {
                int seconds = range(in, "seconds", 0, MAX_WAIT);
                out.addProperty("seconds", seconds);
                if (in.has("upTo") && !in.get("upTo").isJsonNull() && in.get("upTo").getAsDouble() > seconds) out.addProperty("upTo", range(in, "upTo", seconds, MAX_WAIT));
            }
            case "waitFor" -> {
                out.add("event", trigger(obj(in, "event"), true));
                out.addProperty("timeout", in.has("timeout") ? range(in, "timeout", 0, MAX_WAIT) : 0);
            }
            case "waitUntil" -> {
                out.add("cond", condition(obj(in, "cond")));
                out.addProperty("timeout", in.has("timeout") ? range(in, "timeout", 0, MAX_WAIT) : 0);
            }
            case "if" -> {
                out.add("cond", condition(obj(in, "cond")));
                out.add("then", steps(optArr(in, "then"), needs, count, depth + 1));
                out.add("else", steps(optArr(in, "else"), needs, count, depth + 1));
            }
            case "repeat" -> {
                out.addProperty("times", range(in, "times", 1, 1000));
                out.add("steps", steps(optArr(in, "steps"), needs, count, depth + 1));
            }
            case "repeatUntil" -> {
                out.add("cond", condition(obj(in, "cond")));
                out.add("steps", steps(optArr(in, "steps"), needs, count, depth + 1));
            }
            case "forever" -> out.add("steps", steps(optArr(in, "steps"), needs, count, depth + 1));
            case "broadcast" -> out.addProperty("name", match(in, "name", SIGNAL));
            case "set" -> {
                out.addProperty("name", varName(in));
                var value = optString(in, "value");
                if (value.length() > 200) throw new IllegalArgumentException("Keep values under 200 characters");
                out.addProperty("value", value);
            }
            case "change" -> {
                out.addProperty("name", varName(in));
                out.addProperty("by", range(in, "by", -1_000_000, 1_000_000));
            }
            default -> throw new IllegalArgumentException("Unknown block " + type);
        }
        return out;
    }

    static JsonObject condition(JsonObject in) {
        var kind = string(in, "kind");
        if (!CONDITIONS.contains(kind)) throw new IllegalArgumentException("Unknown condition " + kind);
        var out = new JsonObject();
        out.addProperty("kind", kind);
        out.addProperty("not", bool(in, "not"));
        switch (kind) {
            case "health", "food" -> out.addProperty("below", range(in, "below", 1, 20));
            case "text" -> {
                var contains = optText(in, "contains", 100);
                if (contains.isEmpty()) throw new IllegalArgumentException("Give the text to look for");
                out.addProperty("contains", contains);
            }
            case "time" -> {
                out.addProperty("from", match(in, "from", TIME));
                out.addProperty("to", match(in, "to", TIME));
                out.addProperty("zone", zone(in));
            }
            case "chance" -> out.addProperty("percent", range(in, "percent", 1, 99));
            case "var" -> {
                out.addProperty("name", varName(in));
                out.addProperty("op", oneOf(in, "op", Set.of("=", "!=", "<", ">", "contains")));
                var value = optString(in, "value");
                if (value.length() > 100) throw new IllegalArgumentException("Keep values under 100 characters");
                out.addProperty("value", value);
            }
            case "near" -> {
                out.addProperty("x", range(in, "x", -30_000_000, 30_000_000));
                out.addProperty("z", range(in, "z", -30_000_000, 30_000_000));
                out.addProperty("radius", range(in, "radius", 1, 10_000));
            }
            case "dimension" -> out.addProperty("is", oneOf(in, "is", Set.of("overworld", "the_nether", "the_end")));
            case "hasItem" -> {
                var item = string(in, "item").strip().toLowerCase(java.util.Locale.ROOT);
                if (!RESOURCE.matcher(item).matches()) throw new IllegalArgumentException("Not an item id: " + item);
                out.addProperty("item", item.replaceFirst("^minecraft:", ""));
                out.addProperty("count", range(in, "count", 1, 2304));
            }
            default -> {}
        }
        return out;
    }

    // --- helpers ---

    private static String varName(JsonObject in) {
        var name = string(in, "name").strip();
        if (!VAR.matcher(name).matches()) throw new IllegalArgumentException("Variable names start with a small letter and have only letters, digits and _");
        if (RESERVED.contains(name)) throw new IllegalArgumentException("{" + name + "} is already a value, pick another name");
        return name;
    }

    private static String zone(JsonObject in) {
        var zone = optString(in, "zone");
        if (zone.isEmpty()) return "UTC";
        try {
            return ZoneId.of(zone).getId();
        } catch (DateTimeException e) {
            throw new IllegalArgumentException("Unknown time zone " + zone);
        }
    }

    private static String text(JsonObject in, String key, int max) {
        var text = string(in, key).replaceAll("[\\r\\n\\t]+", " ").strip();
        if (text.isEmpty()) throw new IllegalArgumentException("This block needs a message");
        if (text.length() > max) throw new IllegalArgumentException("Keep messages under " + max + " characters");
        return text;
    }

    private static String optText(JsonObject in, String key, int max) {
        var text = optString(in, key).replaceAll("[\\r\\n\\t]+", " ").strip();
        if (text.length() > max) throw new IllegalArgumentException("Keep the text under " + max + " characters");
        return text;
    }

    private static String optPlayer(JsonObject in, String key) {
        var name = optString(in, key).strip();
        if (!name.isEmpty() && !PLAYER.matcher(name).matches()) throw new IllegalArgumentException("Not a player name: " + name);
        return name;
    }

    private static String oneOf(JsonObject in, String key, Set<String> allowed) {
        var value = string(in, key);
        if (!allowed.contains(value)) throw new IllegalArgumentException("Invalid " + key + ": " + value);
        return value;
    }

    private static String match(JsonObject in, String key, Pattern pattern) {
        var value = string(in, key);
        if (!pattern.matcher(value).matches()) throw new IllegalArgumentException("Invalid " + key + ": " + value);
        return value;
    }

    static String string(JsonObject in, String key) {
        var e = in.get(key);
        if (e == null || !e.isJsonPrimitive()) throw new IllegalArgumentException("Missing " + key);
        return e.getAsString();
    }

    static String optString(JsonObject in, String key) {
        var e = in.get(key);
        return e == null || e.isJsonNull() ? "" : e.getAsString();
    }

    private static boolean bool(JsonObject in, String key) {
        var e = in.get(key);
        return e != null && e.isJsonPrimitive() && e.getAsJsonPrimitive().isBoolean() && e.getAsBoolean();
    }

    private static int range(JsonObject in, String key, int min, int max) {
        JsonElement e = in.get(key);
        double value;
        try {
            value = e.getAsDouble();
        } catch (Exception ex) {
            throw new IllegalArgumentException("Missing " + key);
        }
        if (value != Math.rint(value) || value < min || value > max) {
            throw new IllegalArgumentException("Use a whole number from " + min + " to " + max);
        }
        return (int) value;
    }

    private static JsonObject obj(JsonObject in, String key) {
        var e = in.get(key);
        if (e == null || !e.isJsonObject()) throw new IllegalArgumentException("Missing " + key);
        return e.getAsJsonObject();
    }

    private static JsonArray arr(JsonObject in, String key) {
        var e = in.get(key);
        if (e == null || !e.isJsonArray()) throw new IllegalArgumentException("Missing " + key);
        return e.getAsJsonArray();
    }

    private static JsonArray optArr(JsonObject in, String key) {
        return in.has(key) && in.get(key).isJsonArray() ? in.getAsJsonArray(key) : new JsonArray();
    }
}
