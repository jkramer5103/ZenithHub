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
 * step: {id, type, off?, keepGoing?, …}; "if" has cond, then, else; "try" has steps and catch; "repeat",
 * "forever", "repeatUntil" and "forEach" have steps
 * cond: {kind, not, …}; "all" and "any" have conds
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
    /** Conditions inside "all"/"any" groups. */
    static final int MAX_COND_DEPTH = 4;
    static final int MAX_CONDS = 20;

    /** What a flow can start on. */
    public static final Set<String> TRIGGERS = Set.of("manual", "every", "daily", "chat", "whisper", "player", "playerLeft", "attacked", "totem",
        "noTotems", "health", "food", "death", "joined", "left", "crashed", "queueComplete", "queueBelow", "serverRestarting", "controlled", "afk",
        "receive", "onlineFor", "unattended", "arrived", "proxyJoin", "proxyLeave", "entityNear", "entityLeft", "serverJoin", "serverLeave",
        "outOfFood", "dimension", "respawned", "yBelow", "damaged", "itemBelow", "itemAbove", "inventoryFull", "durability", "containerOpened",
        "containerClosed", "tpsBelow", "weather", "blockChanged", "flowFailed", "pathDone");
    /** What "wait until" can wait for: the events among the triggers. */
    static final Set<String> WAITABLE = Set.of("chat", "whisper", "player", "playerLeft", "attacked", "totem", "health", "food", "death", "joined",
        "left", "queueComplete", "queueBelow", "controlled", "afk", "receive", "arrived", "proxyJoin", "proxyLeave", "entityNear", "entityLeft",
        "serverJoin", "serverLeave", "outOfFood", "dimension", "respawned", "yBelow", "damaged", "itemBelow", "itemAbove", "inventoryFull",
        "durability", "containerOpened", "containerClosed", "tpsBelow", "weather", "blockChanged", "pathDone");
    static final Set<String> CONDITIONS = Set.of("health", "food", "playing", "online", "inQueue", "inAfkZone", "earning", "text", "friend",
        "time", "chance", "var", "near", "dimension", "hasItem", "all", "any", "entityNear", "pathing", "containerOpen", "inventoryFull",
        "holding", "block", "alive", "raining", "result");
    /** Values every message can use, so they can't be variable names. */
    static final Set<String> RESERVED = Set.of("player", "message", "health", "food", "x", "y", "z", "time", "date", "server", "dimension",
        "totemsLeft", "position", "previous", "reason", "from", "fromBot", "yaw", "pitch", "ping", "tps", "held", "freeSlots", "nearestPlayer",
        "nearestDistance", "result", "response", "code", "error", "each", "eachCount", "eachDistance", "entity", "distance", "item", "block", "flow", "why",
        "count");
    /** Who or what "entity near" looks for. */
    static final Set<String> ENTITY_WHAT = Set.of("anyone", "stranger", "friend", "player", "hostile", "mob", "type", "item", "vehicle", "any");
    /** Actions that drive the pathfinder, so a flow can wait until they're done. */
    static final Set<String> PATHING = Set.of("goto", "gotoWaypoint", "elytra", "thisway", "getTo", "mine", "pickup", "near", "clickBlock",
        "clickEntity", "breakBlock", "placeBlock", "clearArea", "follow");
    static final Set<String> SCOPES = Set.of("flow", "bot", "shared");
    static final Set<String> LISTS = Set.of("nearbyPlayers", "nearbyEntities", "proxyPlayers", "inventory");
    /** Actions with blocks of their own, or that would let a flow type commands. */
    private static final Set<String> NOT_AS_ACTION = Set.of("command", "notify");

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
        // only start once the trigger happened this often in a short time
        if (in.get("burst") instanceof JsonObject burst && burst.has("count") && range(burst, "count", 1, 100) > 1) {
            var b = new JsonObject();
            b.addProperty("count", range(burst, "count", 1, 100));
            b.addProperty("seconds", range(burst, "seconds", 1, 3600));
            out.add("burst", b);
        }
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
                var match = optString(in, "match");
                out.addProperty("match", match.isEmpty() ? "contains" : oneOf(in, "match", Set.of("contains", "starts", "exact", "mention")));
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
            case "left" -> {
                out.addProperty("reason", optText(in, "reason", 100));
                var why = optString(in, "why");
                out.addProperty("why", why.isEmpty() ? "any" : oneOf(in, "why", Set.of("any", "kicked", "banned", "network", "me")));
            }
            case "queueBelow" -> out.addProperty("position", range(in, "position", 1, 10_000));
            case "controlled" -> out.addProperty("on", oneOf(in, "on", Set.of("start", "stop")));
            case "afk" -> out.addProperty("on", oneOf(in, "on", Set.of("earningStopped", "earningStarted", "leftZone", "enteredZone")));
            case "receive" -> out.addProperty("name", match(in, "name", SIGNAL));
            case "onlineFor", "unattended" -> out.addProperty("minutes", range(in, "minutes", 1, 10_080));
            case "arrived" -> {
                out.addProperty("x", range(in, "x", -30_000_000, 30_000_000));
                out.addProperty("z", range(in, "z", -30_000_000, 30_000_000));
                out.addProperty("radius", range(in, "radius", 1, 10_000));
                optY(in, out);
            }
            case "entityNear", "entityLeft" -> entity(in, out);
            case "serverJoin", "serverLeave" -> {
                var who = optString(in, "who");
                if (who.isEmpty()) who = "anyone";
                if (!Set.of("anyone", "stranger", "friend").contains(who) && !PLAYER.matcher(who).matches()) throw new IllegalArgumentException("Not a player name: " + who);
                out.addProperty("who", who);
            }
            case "dimension" -> {
                var to = optString(in, "to");
                out.addProperty("to", to.isEmpty() ? "any" : oneOf(in, "to", Set.of("any", "overworld", "the_nether", "the_end")));
            }
            case "yBelow" -> out.addProperty("y", range(in, "y", -2048, 2048));
            case "damaged" -> out.addProperty("amount", range(in, "amount", 1, 40));
            case "itemBelow", "itemAbove" -> {
                out.addProperty("item", item(in));
                out.addProperty("count", range(in, "count", kind.equals("itemBelow") ? 1 : 0, 2304));
            }
            case "durability" -> out.addProperty("percent", range(in, "percent", 1, 99));
            case "tpsBelow" -> out.addProperty("tps", range(in, "tps", 1, 19));
            case "weather" -> out.addProperty("on", oneOf(in, "on", Set.of("rainStart", "rainStop")));
            case "blockChanged" -> {
                out.addProperty("x", range(in, "x", -30_000_000, 30_000_000));
                out.addProperty("y", range(in, "y", -2048, 2048));
                out.addProperty("z", range(in, "z", -30_000_000, 30_000_000));
            }
            case "flowFailed" -> {
                // "" is any other flow of this bot
                var flow = optString(in, "flow");
                if (!flow.isEmpty() && !ID.matcher(flow).matches()) throw new IllegalArgumentException("Unknown flow");
                out.addProperty("flow", flow);
            }
            case "proxyJoin", "proxyLeave" -> {
                var who = optString(in, "who");
                if (who.isEmpty()) who = "anyone";
                if (!who.equals("anyone") && !PLAYER.matcher(who).matches()) throw new IllegalArgumentException("Not a player name: " + who);
                out.addProperty("who", who);
            }
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
        if (bool(in, "keepGoing")) out.addProperty("keepGoing", true);
        switch (type) {
            case "action" -> {
                var run = obj(in, "run");
                var action = string(run, "action");
                if (NOT_AS_ACTION.contains(action)) throw new IllegalArgumentException("Not allowed in a flow: " + action);
                // {values} are filled in when it runs; check the rest with stand-ins
                var command = Actions.parse(sample(run));
                needs.add(command.capability());
                out.add("run", run.deepCopy());
                // pathfinder actions can wait: until done (with a time limit), or for a while and then stop
                if (PATHING.contains(action)) {
                    var wait = optString(in, "wait");
                    if (wait.isEmpty()) wait = "no";
                    if (!Set.of("no", "done", "for").contains(wait)) throw new IllegalArgumentException("Invalid wait: " + wait);
                    out.addProperty("wait", wait);
                    if (!wait.equals("no")) out.addProperty("timeout", range(in, "timeout", 1, MAX_WAIT));
                }
            }
            case "reconnect" -> {
                int seconds = range(in, "seconds", 0, MAX_WAIT);
                out.addProperty("seconds", seconds);
                if (in.has("upTo") && !in.get("upTo").isJsonNull() && in.get("upTo").getAsDouble() > seconds) out.addProperty("upTo", range(in, "upTo", seconds, MAX_WAIT));
                out.addProperty("timeout", in.has("timeout") ? range(in, "timeout", 0, MAX_WAIT) : 120);
                needs.add(Capability.CONTROL);
            }
            case "try" -> {
                out.add("steps", steps(optArr(in, "steps"), needs, count, depth + 1));
                out.add("catch", steps(optArr(in, "catch"), needs, count, depth + 1));
            }
            case "forEach" -> {
                out.addProperty("list", oneOf(in, "list", LISTS));
                out.add("steps", steps(optArr(in, "steps"), needs, count, depth + 1));
            }
            case "runFlow" -> {
                out.addProperty("flow", match(in, "flow", ID));
                out.addProperty("wait", bool(in, "wait"));
            }
            case "webhook" -> {
                var url = string(in, "url").strip();
                if (url.length() > 500 || !url.matches("(?i)https?://[^\\s]+")) throw new IllegalArgumentException("Give a web address starting with http:// or https://");
                // the address itself can't come from someone else's chat
                var host = url.replaceFirst("(?i)https?://", "").split("[/?#]", 2)[0];
                if (host.contains("{")) throw new IllegalArgumentException("The server part of the address can't contain {values}");
                out.addProperty("url", url);
                out.addProperty("method", oneOf(in, "method", Set.of("GET", "POST")));
                var body = optString(in, "body");
                if (body.length() > 2000) throw new IllegalArgumentException("Keep the body under 2000 characters");
                out.addProperty("body", body);
            }
            case "holdItem" -> {
                out.addProperty("item", item(in));
                needs.add(Capability.PLAY);
            }
            case "dropItem" -> {
                out.addProperty("item", item(in));
                out.addProperty("all", bool(in, "all"));
                needs.add(Capability.PLAY);
            }
            case "lookAt" -> {
                var target = oneOf(in, "target", Set.of("position", "entity"));
                out.addProperty("target", target);
                if (target.equals("position")) {
                    out.addProperty("x", range(in, "x", -30_000_000, 30_000_000));
                    out.addProperty("y", range(in, "y", -2048, 2048));
                    out.addProperty("z", range(in, "z", -30_000_000, 30_000_000));
                } else {
                    entity(in, out);
                }
                needs.add(Capability.PLAY);
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
            case "connect" -> {
                // wait until in the game, failing if that doesn't happen in time
                if (bool(in, "wait")) {
                    out.addProperty("wait", true);
                    out.addProperty("timeout", in.has("timeout") ? range(in, "timeout", 5, MAX_WAIT) : 120);
                }
            }
            case "disconnect", "stop" -> {}
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
            case "broadcast" -> {
                out.addProperty("name", match(in, "name", SIGNAL));
                // "" is this bot, "all" every bot the owner controls, else an instance id
                var to = optString(in, "to");
                if (!to.isEmpty() && !ID.matcher(to).matches()) throw new IllegalArgumentException("Unknown bot");
                out.addProperty("to", to);
            }
            case "set" -> {
                out.addProperty("name", varName(in));
                out.addProperty("scope", scope(in));
                var value = optString(in, "value");
                if (value.length() > 200) throw new IllegalArgumentException("Keep values under 200 characters");
                out.addProperty("value", value);
            }
            case "change" -> {
                out.addProperty("name", varName(in));
                out.addProperty("scope", scope(in));
                out.addProperty("by", range(in, "by", -1_000_000, 1_000_000));
            }
            default -> throw new IllegalArgumentException("Unknown block " + type);
        }
        return out;
    }

    static JsonObject condition(JsonObject in) {
        return condition(in, 0, new int[] {0});
    }

    private static JsonObject condition(JsonObject in, int depth, int[] count) {
        if (++count[0] > MAX_CONDS) throw new IllegalArgumentException("Use at most " + MAX_CONDS + " conditions in one block");
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
                out.addProperty("scope", scope(in));
                out.addProperty("op", oneOf(in, "op", Set.of("=", "!=", "<", ">", "contains")));
                var value = optString(in, "value");
                if (value.length() > 100) throw new IllegalArgumentException("Keep values under 100 characters");
                out.addProperty("value", value);
            }
            case "near" -> {
                out.addProperty("x", range(in, "x", -30_000_000, 30_000_000));
                out.addProperty("z", range(in, "z", -30_000_000, 30_000_000));
                out.addProperty("radius", range(in, "radius", 1, 10_000));
                optY(in, out);
            }
            case "all", "any" -> {
                if (depth >= MAX_COND_DEPTH) throw new IllegalArgumentException("Conditions are nested too deeply");
                var conds = new JsonArray();
                for (var c : optArr(in, "conds")) {
                    if (!c.isJsonObject()) throw new IllegalArgumentException("Invalid condition");
                    conds.add(condition(c.getAsJsonObject(), depth + 1, count));
                }
                if (conds.isEmpty()) throw new IllegalArgumentException("Add at least one condition to the group");
                out.add("conds", conds);
            }
            case "entityNear" -> entity(in, out);
            case "result" -> out.addProperty("is", oneOf(in, "is", Set.of("done", "timeout", "failed")));
            case "holding" -> out.addProperty("item", item(in));
            case "block" -> {
                out.addProperty("x", range(in, "x", -30_000_000, 30_000_000));
                out.addProperty("y", range(in, "y", -2048, 2048));
                out.addProperty("z", range(in, "z", -30_000_000, 30_000_000));
                out.addProperty("is", item(in, "is"));
            }
            case "dimension" -> out.addProperty("is", oneOf(in, "is", Set.of("overworld", "the_nether", "the_end")));
            case "hasItem" -> {
                out.addProperty("item", item(in));
                out.addProperty("count", range(in, "count", 1, 2304));
            }
            default -> {}
        }
        return out;
    }

    // --- helpers ---

    /** {player}, {x}, {count:item} and friends, in the fields of an action. */
    static final Pattern VALUE = Pattern.compile("\\{[a-zA-Z][a-zA-Z0-9_]*(?::[a-z0-9_]{1,64})?}");
    /** Action fields that are numbers, so a value in them stands in for one. */
    static final Set<String> NUMERIC = Set.of("x", "y", "z", "slot", "from", "to", "blocks", "range", "port", "yaw", "pitch");
    /** Action fields that pick from a fixed list: no values there. */
    private static final Set<String> FIXED = Set.of("action", "module", "button", "mode");

    /** A copy of an action with every {value} replaced by something valid, so the rest of it can be checked. */
    static JsonObject sample(JsonObject run) {
        var out = new JsonObject();
        for (var e : run.entrySet()) {
            var v = e.getValue();
            if (v instanceof JsonObject o) out.add(e.getKey(), sample(o));
            else if (hasValue(e.getKey(), v)) out.addProperty(e.getKey(), NUMERIC.contains(e.getKey()) ? "0" : "a");
            else out.add(e.getKey(), v.deepCopy());
        }
        return out;
    }

    static boolean hasValue(String key, JsonElement v) {
        return !FIXED.contains(key) && v.isJsonPrimitive() && v.getAsJsonPrimitive().isString() && VALUE.matcher(v.getAsString()).find();
    }

    /** An entity filter with a radius: who/what, plus a name or a mob type where that needs one. */
    private static void entity(JsonObject in, JsonObject out) {
        var what = optString(in, "what");
        if (what.isEmpty()) what = "anyone";
        if (!ENTITY_WHAT.contains(what)) throw new IllegalArgumentException("Invalid what: " + what);
        out.addProperty("what", what);
        if (what.equals("player")) out.addProperty("name", match(in, "name", PLAYER));
        // "mob", not "type": a step already has a type
        if (what.equals("type")) out.addProperty("mob", item(in, "mob"));
        out.addProperty("radius", range(in, "radius", 1, 64));
    }

    private static void optY(JsonObject in, JsonObject out) {
        if (in.has("y") && !in.get("y").isJsonNull() && !optString(in, "y").isEmpty()) out.addProperty("y", range(in, "y", -2048, 2048));
    }

    private static String scope(JsonObject in) {
        var scope = optString(in, "scope");
        return scope.isEmpty() ? "flow" : oneOf(in, "scope", SCOPES);
    }

    private static String item(JsonObject in) {
        return item(in, "item");
    }

    /** An item, block or mob id, without "minecraft:". */
    private static String item(JsonObject in, String key) {
        var item = string(in, key).strip().toLowerCase(java.util.Locale.ROOT).replace(' ', '_');
        if (!RESOURCE.matcher(item).matches()) throw new IllegalArgumentException("Not an id: " + item);
        return item.replaceFirst("^minecraft:", "");
    }

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
