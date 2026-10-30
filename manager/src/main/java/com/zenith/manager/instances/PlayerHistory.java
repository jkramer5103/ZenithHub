package com.zenith.manager.instances;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.zenith.manager.ManagerConfig;
import com.zenith.manager.util.Json;
import com.zenith.manager.util.SecureFiles;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashMap;
import java.util.Map;

/**
 * Who joined, left, switched and was kicked, per instance. The last {@value #MAX_ENTRIES} entries are kept in
 * {@code manager/history/<instance>.json}.
 *
 * A swap reconnects the player, so a leave followed by a join of the same name shortly after becomes one
 * "switched" entry. Kicks and swaps done through the web UI are noted first, so the entries that follow
 * name who did them.
 */
public final class PlayerHistory {
    private static final Logger LOG = LoggerFactory.getLogger("PlayerHistory");
    static final int MAX_ENTRIES = 200;
    private static final long SWITCH_WINDOW_MS = 15_000;
    private static final long ACTION_WINDOW_MS = 10_000;

    /** {@code event}: joined, left, switched or kicked. {@code spectator} is the role after the event. */
    public record Entry(long ts, String event, String name, String uuid, boolean spectator, String reason, String actor) {
        JsonObject toJson() {
            var json = new JsonObject();
            json.addProperty("ts", ts);
            json.addProperty("event", event);
            json.addProperty("name", name);
            if (uuid != null) json.addProperty("uuid", uuid);
            json.addProperty("spectator", spectator);
            if (reason != null) json.addProperty("reason", reason);
            if (actor != null) json.addProperty("actor", actor);
            return json;
        }
    }

    /** A kick or swap someone started; {@code name} null means everyone. */
    private record Pending(String kind, String name, String actor, long ts) { }

    private final Path dir;
    private final Map<String, Deque<Entry>> entries = new HashMap<>();
    private final Map<String, Deque<Pending>> pending = new HashMap<>();

    public PlayerHistory(ManagerConfig config) {
        this(config.dataDir().resolve("manager").resolve("history"));
    }

    public PlayerHistory(Path dir) {
        this.dir = dir;
    }

    /** Newest first. */
    public synchronized JsonArray list(String instanceId) {
        var result = new JsonArray();
        load(instanceId).descendingIterator().forEachRemaining(e -> result.add(e.toJson()));
        return result;
    }

    /** Remembers a kick or swap from the web UI, so the leave or switch it causes names who did it. */
    public synchronized void noteAction(String instanceId, String kind, String name, String actor) {
        var queue = pending.computeIfAbsent(instanceId, k -> new ArrayDeque<>());
        queue.addLast(new Pending(kind, name, actor, System.currentTimeMillis()));
        while (queue.size() > 20) queue.removeFirst();
    }

    /** Drops the newest matching noted action, when the kick or swap it was for failed. */
    public synchronized void cancelAction(String instanceId, String kind, String name, String actor) {
        var queue = pending.get(instanceId);
        if (queue == null) return;
        for (var it = queue.descendingIterator(); it.hasNext(); ) {
            var p = it.next();
            if (p.kind().equals(kind) && java.util.Objects.equals(p.name(), name) && p.actor().equals(actor)) {
                it.remove();
                return;
            }
        }
    }

    /** A "player" event from Zenith's control API. */
    public synchronized void record(String instanceId, JsonObject event) {
        var name = str(event, "name");
        var kind = str(event, "event");
        if (name == null || kind == null) return;
        long ts = event.has("ts") ? event.get("ts").getAsLong() : System.currentTimeMillis();
        boolean spectator = event.has("spectator") && event.get("spectator").getAsBoolean();
        var list = load(instanceId);
        if (kind.equals("joined")) {
            var left = recentLeave(list, name, ts);
            // only a change of role is a switch; a relog or a trip through the lobby stays a leave and a join
            if (left != null && left.spectator() != spectator) {
                list.remove(left);
                var swap = take(instanceId, "swap", name, ts);
                add(instanceId, new Entry(ts, "switched", name, str(event, "uuid"), spectator, null, swap != null ? swap.actor() : null));
                return;
            }
            add(instanceId, new Entry(ts, "joined", name, str(event, "uuid"), spectator, null, null));
        } else if (kind.equals("left")) {
            var kick = take(instanceId, "kick", name, ts);
            if (kick != null) {
                add(instanceId, new Entry(ts, "kicked", name, str(event, "uuid"), spectator, null, kick.actor()));
            } else {
                add(instanceId, new Entry(ts, "left", name, str(event, "uuid"), spectator, reason(str(event, "reason")), null));
            }
        }
    }

    public synchronized void forget(String instanceId) {
        entries.remove(instanceId);
        pending.remove(instanceId);
        try {
            Files.deleteIfExists(file(instanceId));
        } catch (Exception e) {
            LOG.warn("Couldn't delete the history of {}", instanceId, e);
        }
    }

    private Entry recentLeave(Deque<Entry> list, String name, long ts) {
        for (var it = list.descendingIterator(); it.hasNext(); ) {
            var e = it.next();
            if (ts - e.ts() > SWITCH_WINDOW_MS) return null;
            if (e.name().equalsIgnoreCase(name)) return e.event().equals("left") ? e : null;
        }
        return null;
    }

    /** The matching pending action, removed once used. Kicking everyone matches every name. */
    private Pending take(String instanceId, String kind, String name, long ts) {
        var queue = pending.get(instanceId);
        if (queue == null) return null;
        queue.removeIf(p -> ts - p.ts() > ACTION_WINDOW_MS);
        for (var p : queue) {
            if (!p.kind().equals(kind)) continue;
            if (p.name() == null) return p;
            if (p.name().equalsIgnoreCase(name)) {
                queue.remove(p);
                return p;
            }
        }
        return null;
    }

    /** Transfers are how swaps and the lobby work; they're not worth showing as a reason. */
    private static String reason(String reason) {
        if (reason == null || reason.startsWith("Transferring to ")) return null;
        return reason.length() > 200 ? reason.substring(0, 200) : reason;
    }

    private void add(String instanceId, Entry entry) {
        var list = load(instanceId);
        list.addLast(entry);
        while (list.size() > MAX_ENTRIES) list.removeFirst();
        save(instanceId, list);
    }

    private Deque<Entry> load(String instanceId) {
        return entries.computeIfAbsent(instanceId, id -> {
            var list = new ArrayDeque<Entry>();
            var file = file(id);
            if (!Files.exists(file)) return list;
            try {
                for (var e : JsonParser.parseString(Files.readString(file)).getAsJsonArray()) {
                    var o = e.getAsJsonObject();
                    list.add(new Entry(o.get("ts").getAsLong(), str(o, "event"), str(o, "name"), str(o, "uuid"),
                        o.has("spectator") && o.get("spectator").getAsBoolean(), str(o, "reason"), str(o, "actor")));
                }
            } catch (Exception e) {
                LOG.warn("Couldn't read the history of {}", id, e);
            }
            return list;
        });
    }

    private void save(String instanceId, Deque<Entry> list) {
        var array = new JsonArray();
        list.forEach(e -> array.add(e.toJson()));
        try {
            SecureFiles.writeString(file(instanceId), Json.COMPACT.toJson(array));
        } catch (Exception e) {
            LOG.warn("Couldn't save the history of {}", instanceId, e);
        }
    }

    private Path file(String instanceId) {
        return dir.resolve(instanceId + ".json");
    }

    private static String str(JsonObject o, String key) {
        return o.has(key) && !o.get(key).isJsonNull() ? o.get(key).getAsString() : null;
    }
}
