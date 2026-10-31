package com.zenith.manager.instances;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.zenith.manager.ManagerConfig;
import com.zenith.manager.util.Json;
import com.zenith.manager.util.SecureFiles;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashMap;
import java.util.Map;

/**
 * Time in the AFK zone and what it paid, per instance, from Zenith's "afk" events. Kept in
 * {@code manager/afk/<instance>.json}: the reward counter at most every {@value #SAMPLE_EVERY_MS} ms for
 * {@value #KEEP_DAYS} days, and the last {@value #MAX_SESSIONS} stays in the zone.
 */
public final class AfkHistory {
    private static final Logger LOG = LoggerFactory.getLogger("AfkHistory");
    static final long SAMPLE_EVERY_MS = 5 * 60_000;
    static final int KEEP_DAYS = 14;
    static final int MAX_SESSIONS = 100;
    /** Saving every reward change would write once a minute; samples and sessions are what matter. */
    private static final long SAVE_EVERY_MS = 60_000;

    public record Sample(long ts, double value) { }

    /** A stay in the zone; {@code end} 0 while it lasts. */
    public static final class Session {
        long start;
        long end;
        Double startReward;
        Double endReward;
        String endReason;

        JsonObject toJson() {
            var json = new JsonObject();
            json.addProperty("start", start);
            if (end > 0) json.addProperty("end", end);
            if (startReward != null) json.addProperty("startReward", startReward);
            if (endReward != null) json.addProperty("endReward", endReward);
            if (endReason != null) json.addProperty("endReason", endReason);
            return json;
        }
    }

    private static final class Data {
        final Deque<Sample> samples = new ArrayDeque<>();
        final Deque<Session> sessions = new ArrayDeque<>();
        long savedAt;
    }

    private final Path dir;
    private final Map<String, Data> data = new HashMap<>();

    public AfkHistory(ManagerConfig config) {
        this(config.dataDir().resolve("manager").resolve("afk"));
    }

    public AfkHistory(Path dir) {
        this.dir = dir;
    }

    /** An "afk" event from Zenith's control API. */
    public synchronized void record(String instanceId, JsonObject event) {
        long ts = event.has("ts") ? event.get("ts").getAsLong() : System.currentTimeMillis();
        var state = event.has("state") ? event.get("state").getAsString() : null;
        Double reward = event.has("reward") && !event.get("reward").isJsonNull() ? event.get("reward").getAsDouble() : null;
        var d = load(instanceId);
        boolean changed = false;

        if (reward != null) {
            var last = d.samples.peekLast();
            if (last == null || last.value() != reward) {
                // points at least a sample window apart, plus a last one that follows the counter
                var all = d.samples.toArray(Sample[]::new);
                var prev = all.length >= 2 ? all[all.length - 2] : null;
                if (prev != null && ts - prev.ts() < SAMPLE_EVERY_MS) d.samples.removeLast();
                d.samples.addLast(new Sample(ts, reward));
                changed = last == null || prev == null || ts - prev.ts() >= SAMPLE_EVERY_MS;
            }
            long cutoff = ts - KEEP_DAYS * 86_400_000L;
            while (!d.samples.isEmpty() && d.samples.peekFirst().ts() < cutoff) d.samples.removeFirst();
        }

        var open = d.sessions.peekLast();
        if (open != null && open.end > 0) open = null;
        if ("IN_ZONE".equals(state)) {
            long since = event.has("since") ? event.get("since").getAsLong() : ts;
            if (open != null && since > open.start + 1_000) {
                // a stay we never saw end (the instance stopped): it ended by the last time we heard of it
                open.end = Math.max(open.start, d.samples.isEmpty() ? open.start : d.samples.peekLast().ts());
                open.endReason = "Disconnected";
                open = null;
            }
            if (open == null) {
                var session = new Session();
                session.start = since;
                session.startReward = event.has("rewardAtEntry") ? event.get("rewardAtEntry").getAsDouble() : reward;
                d.sessions.addLast(session);
                while (d.sessions.size() > MAX_SESSIONS) d.sessions.removeFirst();
                changed = true;
            } else {
                // Zenith reads the counter a moment after entering
                if (event.has("rewardAtEntry")) open.startReward = event.get("rewardAtEntry").getAsDouble();
                else if (open.startReward == null && reward != null) open.startReward = reward;
                if (reward != null) open.endReward = reward;
            }
        } else if (state != null && open != null) {
            open.end = ts;
            if (reward != null) open.endReward = reward;
            open.endReason = "OFFLINE".equals(state) ? "Disconnected" : str(event, "leftReason");
            changed = true;
        }

        if (changed || ts - d.savedAt >= SAVE_EVERY_MS) save(instanceId, d, ts);
    }

    /**
     * What the counter rose by since {@code from}: only the rises, so spending in between doesn't count against it.
     * The last sample before {@code from} is the starting point. Null without any samples.
     */
    public synchronized @Nullable Double gainedSince(String instanceId, long from) {
        var d = load(instanceId);
        if (d.samples.isEmpty()) return null;
        double gained = 0;
        Sample previous = null;
        for (var sample : d.samples) {
            if (sample.ts() < from) {
                previous = sample;
                continue;
            }
            if (previous != null && sample.value() > previous.value()) gained += sample.value() - previous.value();
            previous = sample;
        }
        return gained;
    }

    /** {@code samples} oldest first, {@code sessions} newest first. */
    public synchronized JsonObject get(String instanceId) {
        var d = load(instanceId);
        var json = new JsonObject();
        var samples = new JsonArray();
        d.samples.forEach(s -> {
            var a = new JsonArray();
            a.add(s.ts());
            a.add(s.value());
            samples.add(a);
        });
        json.add("samples", samples);
        var sessions = new JsonArray();
        d.sessions.descendingIterator().forEachRemaining(s -> sessions.add(s.toJson()));
        json.add("sessions", sessions);
        return json;
    }

    public synchronized void forget(String instanceId) {
        data.remove(instanceId);
        try {
            Files.deleteIfExists(file(instanceId));
        } catch (Exception e) {
            LOG.warn("Couldn't delete the AFK history of {}", instanceId, e);
        }
    }

    private Data load(String instanceId) {
        return data.computeIfAbsent(instanceId, id -> {
            var d = new Data();
            var file = file(id);
            if (!Files.exists(file)) return d;
            try {
                var json = JsonParser.parseString(Files.readString(file)).getAsJsonObject();
                for (var e : json.getAsJsonArray("samples")) {
                    var a = e.getAsJsonArray();
                    d.samples.add(new Sample(a.get(0).getAsLong(), a.get(1).getAsDouble()));
                }
                for (var e : json.getAsJsonArray("sessions")) {
                    var o = e.getAsJsonObject();
                    var s = new Session();
                    s.start = o.get("start").getAsLong();
                    s.end = o.has("end") ? o.get("end").getAsLong() : 0;
                    s.startReward = o.has("startReward") ? o.get("startReward").getAsDouble() : null;
                    s.endReward = o.has("endReward") ? o.get("endReward").getAsDouble() : null;
                    s.endReason = str(o, "endReason");
                    d.sessions.add(s);
                }
                // the manager went away mid-stay: we don't know when it ended
                var open = d.sessions.peekLast();
                if (open != null && open.end == 0) {
                    open.end = d.samples.isEmpty() ? open.start : Math.max(open.start, d.samples.peekLast().ts());
                    open.endReason = "ZenithHub restarted";
                }
            } catch (Exception e) {
                LOG.warn("Couldn't read the AFK history of {}", id, e);
            }
            return d;
        });
    }

    private void save(String instanceId, Data d, long now) {
        d.savedAt = now;
        var json = get(instanceId);
        // stored oldest first, like the samples
        var sessions = new JsonArray();
        d.sessions.forEach(s -> sessions.add(s.toJson()));
        json.add("sessions", sessions);
        try {
            SecureFiles.writeString(file(instanceId), Json.COMPACT.toJson(json));
        } catch (Exception e) {
            LOG.warn("Couldn't save the AFK history of {}", instanceId, e);
        }
    }

    private Path file(String instanceId) {
        return dir.resolve(instanceId + ".json");
    }

    private static String str(JsonObject o, String key) {
        return o.has(key) && !o.get(key).isJsonNull() ? o.get(key).getAsString() : null;
    }
}
