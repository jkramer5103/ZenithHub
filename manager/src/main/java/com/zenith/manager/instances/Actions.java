package com.zenith.manager.instances;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.zenith.manager.auth.Capability;

import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Turns the web UI's structured actions into Zenith commands.
 *
 * Every argument is checked against a strict pattern, so nothing a user types ends up as extra command syntax.
 * Each action names the capability it needs.
 */
public final class Actions {
    private static final Pattern PLAYER = Pattern.compile("[A-Za-z0-9_]{1,16}");
    private static final Pattern RESOURCE = Pattern.compile("(minecraft:)?[a-z0-9_]{1,64}");
    /** pearl, waypoint and task ids: Zenith reads them up to the next space */
    private static final Pattern ID = Pattern.compile("[A-Za-z0-9_.\\-]{1,32}");
    private static final Pattern HOST = Pattern.compile("[A-Za-z0-9.\\-]{1,253}");
    private static final int MAX_COORD = 30_000_000;

    /** Task triggers, as Zenith's tasks command names them. */
    public static final Set<String> TASK_EVENTS = Set.of("connect", "death", "disconnect", "online", "playerConnect", "playerDisconnect");

    /** {@code alsoNeeds}: a second capability, for tasks that run an action later. */
    public record Command(Capability capability, String command, Capability alsoNeeds) {
        Command(Capability capability, String command) {
            this(capability, command, null);
        }
    }

    private Actions() {}

    public static Command parse(JsonObject body) {
        var action = string(body, "action");
        return switch (action) {
            // --- movement (pathfinder) ---
            case "goto" -> play("pathfinder goto " + coords(body, true));
            case "gotoWaypoint" -> play("pathfinder goto " + id(body, "waypoint"));
            case "elytra" -> play("pathfinder elytra " + coords(body, true));
            case "follow" -> play(body.has("player") && !body.get("player").isJsonNull()
                ? "pathfinder follow " + player(body, "player")
                : "pathfinder follow");
            case "thisway" -> play("pathfinder thisway " + integer(body, "blocks", -100_000, 100_000));
            case "getTo" -> play("pathfinder getTo " + resource(body, "block"));
            case "mine" -> play("pathfinder mine " + resource(body, "block"));
            case "pickup" -> play(body.has("item") && !body.get("item").isJsonNull()
                ? "pathfinder pickup " + resource(body, "item")
                : "pathfinder pickup");
            case "near" -> {
                int range = integer(body, "range", 1, 1000);
                yield play("pathfinder near " + coords(body, false) + " " + (range * range));
            }
            case "clickBlock" -> play("pathfinder click " + oneOf(body, "button", Set.of("left", "right")) + " "
                + (body.has("waypoint") ? id(body, "waypoint") : coords(body, false)));
            case "clickEntity" -> play("pathfinder click " + oneOf(body, "button", Set.of("left", "right")) + " entity " + resource(body, "entity"));
            case "breakBlock" -> play("pathfinder break " + coords(body, false));
            case "placeBlock" -> play("pathfinder place " + coords(body, false) + " " + resource(body, "item"));
            case "clearArea" -> play("pathfinder clearArea "
                + coords(body.getAsJsonObject("from"), false) + " " + coords(body.getAsJsonObject("to"), false));
            case "stop" -> play("pathfinder stop");

            // --- interacting ---
            case "click" -> play(click(body));
            case "clickStop" -> play("click stop");
            case "rotate" -> play("rotate " + decimal(body, "yaw", -180, 180) + " " + decimal(body, "pitch", -90, 90));
            case "respawn" -> play("respawn");

            // --- waypoints: they're settings ---
            case "addWaypoint" -> new Command(Capability.SETTINGS, "waypoints add " + id(body, "id") + " "
                + (body.has("dimension") ? resource(body, "dimension") + " " : "") + coords(body, false));
            case "deleteWaypoint" -> new Command(Capability.SETTINGS, "waypoints del " + id(body, "id"));

            // --- inventory ---
            case "hold" -> play("inventory hold " + integer(body, "slot", 0, 200));
            case "swap" -> play("inventory swap " + integer(body, "from", 0, 200) + " " + integer(body, "to", 0, 200));
            case "drop" -> play("inventory drop " + (bool(body, "stack") ? "stack " : "") + integer(body, "slot", 0, 200));
            case "clickSlot" -> play("inventory click " + oneOf(body, "button", Set.of("left", "right")) + " " + integer(body, "slot", 0, 200));
            case "closeContainer" -> play("inventory close");
            case "withdraw" -> play("inventory withdraw");
            case "deposit" -> play("inventory deposit");

            // --- connection and players ---
            case "requeue" -> new Command(Capability.CONTROL, "requeue");
            case "kick" -> new Command(Capability.CONTROL, body.has("player") && !body.get("player").isJsonNull()
                ? "kick " + player(body, "player")
                : "kick");
            case "transfer" -> new Command(Capability.CONTROL, transfer(body));
            case "makePlayer" -> new Command(Capability.CONTROL, "controller set " + player(body, "player"));
            case "makeSpectator" -> new Command(Capability.CONTROL, "controller release");
            case "replayStart" -> new Command(Capability.CONTROL, "replay start");
            case "replayStop" -> new Command(Capability.CONTROL, "replay stop");

            // --- AFK zone ---
            case "afkKeep" -> new Command(Capability.CONTROL, "afkZone keep " + (bool(body, "keep") ? "on" : "off"));
            case "afkEnter" -> play("afkZone enter");
            case "afkLeave" -> play("afkZone leave");

            // --- pearls ---
            case "loadPearl" -> new Command(Capability.PEARLS, "pearlLoader load " + id(body, "pearl"));

            // --- tasks ---
            case "addTask" -> addTask(body);
            case "deleteTask" -> new Command(Capability.CONTROL, "tasks del " + id(body, "id"));

            // --- free text, for developer mode ---
            case "command" -> {
                var command = string(body, "command").replace('\n', ' ').trim();
                if (command.isEmpty()) throw new IllegalArgumentException("Empty command");
                yield new Command(Capability.COMMANDS, command);
            }
            default -> throw new IllegalArgumentException("Unknown action: " + action);
        };
    }

    /** A task runs another action later. Adding one needs both CONTROL and whatever the action itself needs. */
    private static Command addTask(JsonObject body) {
        var id = id(body, "id");
        if (!body.has("run") || !body.get("run").isJsonObject()) throw new IllegalArgumentException("Missing run");
        var run = parse(body.getAsJsonObject("run"));
        if (run.command().startsWith("tasks ")) throw new IllegalArgumentException("A task can't change tasks");
        var when = oneOf(body, "when", Set.of("after", "every", "event"));
        var prefix = switch (when) {
            case "after" -> "tasks add timed " + id + " " + integer(body, "delaySeconds", 1, 31_536_000) + "s ";
            case "every" -> "tasks add interval " + id + " "
                + integer(body, "startSeconds", 0, 31_536_000) + "s "
                + integer(body, "intervalSeconds", 1, 31_536_000) + "s ";
            default -> "tasks add event " + id + " " + oneOf(body, "event", TASK_EVENTS) + " "
                + (bool(body, "repeat") ? "repeat" : "once") + " ";
        };
        return new Command(Capability.CONTROL, prefix + run.command(), run.capability());
    }

    private static String click(JsonObject body) {
        var button = oneOf(body, "button", Set.of("left", "right"));
        var mode = body.has("mode") ? oneOf(body, "mode", Set.of("once", "hold", "useItem")) : "once";
        return switch (mode) {
            case "hold" -> "click " + button + " hold";
            case "useItem" -> {
                if (!button.equals("right")) throw new IllegalArgumentException("Only right click can use an item");
                yield "click right useItem";
            }
            default -> "click " + button;
        };
    }

    private static String transfer(JsonObject body) {
        var address = string(body, "address").trim();
        if (!HOST.matcher(address).matches()) throw new IllegalArgumentException("Invalid address");
        var command = new StringBuilder("transfer ").append(address);
        if (body.has("port") && !body.get("port").isJsonNull()) command.append(' ').append(integer(body, "port", 1, 65535));
        if (body.has("player") && !body.get("player").isJsonNull()) command.append(' ').append(player(body, "player"));
        return command.toString();
    }

    private static String coords(JsonObject body, boolean yOptional) {
        if (body == null) throw new IllegalArgumentException("Missing coordinates");
        int x = integer(body, "x", -MAX_COORD, MAX_COORD);
        int z = integer(body, "z", -MAX_COORD, MAX_COORD);
        var y = body.get("y");
        if (y == null || y.isJsonNull()) {
            if (!yOptional) throw new IllegalArgumentException("Missing y");
            return x + " " + z;
        }
        return x + " " + integer(body, "y", -2048, 2048) + " " + z;
    }

    private static Command play(String command) {
        return new Command(Capability.PLAY, command);
    }

    private static String string(JsonObject body, String key) {
        JsonElement e = body.get(key);
        if (e == null || e.isJsonNull() || !e.isJsonPrimitive()) throw new IllegalArgumentException("Missing " + key);
        return e.getAsString();
    }

    private static String player(JsonObject body, String key) {
        var name = string(body, key).trim();
        if (!PLAYER.matcher(name).matches()) throw new IllegalArgumentException("Invalid player name");
        return name;
    }

    private static String id(JsonObject body, String key) {
        var id = string(body, key).trim();
        if (!ID.matcher(id).matches()) throw new IllegalArgumentException("Invalid " + key + ": use letters, numbers, _ . or -");
        return id;
    }

    private static String resource(JsonObject body, String key) {
        var value = string(body, key).trim().toLowerCase(Locale.ROOT).replace(' ', '_');
        if (!RESOURCE.matcher(value).matches()) throw new IllegalArgumentException("Invalid " + key);
        return value.startsWith("minecraft:") ? value.substring(10) : value;
    }

    private static String oneOf(JsonObject body, String key, Set<String> allowed) {
        var value = string(body, key);
        if (!allowed.contains(value)) throw new IllegalArgumentException("Invalid " + key);
        return value;
    }

    private static boolean bool(JsonObject body, String key) {
        var e = body.get(key);
        return e != null && e.isJsonPrimitive() && e.getAsBoolean();
    }

    private static int integer(JsonObject body, String key, int min, int max) {
        try {
            var value = body.get(key).getAsDouble();
            if (value != Math.rint(value)) throw new IllegalArgumentException(key + " must be a whole number");
            if (value < min || value > max) throw new IllegalArgumentException(key + " must be between " + min + " and " + max);
            return (int) value;
        } catch (NullPointerException | UnsupportedOperationException | NumberFormatException | IllegalStateException e) {
            throw new IllegalArgumentException("Missing " + key);
        }
    }

    private static String decimal(JsonObject body, String key, double min, double max) {
        try {
            var value = body.get(key).getAsDouble();
            if (!(value >= min && value <= max)) throw new IllegalArgumentException(key + " must be between " + min + " and " + max);
            return String.format(Locale.ROOT, "%.2f", value);
        } catch (NullPointerException | UnsupportedOperationException | NumberFormatException | IllegalStateException e) {
            throw new IllegalArgumentException("Missing " + key);
        }
    }

    /** Zenith's names for task trigger events, as reported by its control API. */
    public static final Map<String, String> TASK_EVENT_CLASSES = Map.of(
        "ClientConnectEvent", "connect",
        "ClientDeathEvent", "death",
        "ClientDisconnectEvent", "disconnect",
        "ClientOnlineEvent", "online",
        "PlayerConnectedEvent", "playerConnect",
        "PlayerDisconnectedEvent", "playerDisconnect"
    );
}
