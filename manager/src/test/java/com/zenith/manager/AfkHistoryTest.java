package com.zenith.manager;

import com.google.gson.JsonObject;
import com.zenith.manager.instances.AfkHistory;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;

class AfkHistoryTest {
    @TempDir Path dir;

    private static JsonObject event(String state, long ts, Double reward, Long since) {
        var json = new JsonObject();
        json.addProperty("state", state);
        json.addProperty("ts", ts);
        if (reward != null) json.addProperty("reward", reward);
        if (since != null) json.addProperty("since", since);
        return json;
    }

    @Test
    void staysBecomeSessionsWithTheirEarnings() {
        var history = new AfkHistory(dir);
        long t = 1_000_000;
        history.record("i", event("IN_ZONE", t, 10.0, t));
        history.record("i", event("IN_ZONE", t + 60_000, 11.0, t));
        history.record("i", event("IN_ZONE", t + 120_000, 12.0, t));
        var left = event("OUTSIDE", t + 130_000, 12.0, null);
        left.addProperty("leftReason", "Moved away from the zone");
        history.record("i", left);

        var sessions = history.get("i").getAsJsonArray("sessions");
        assertEquals(1, sessions.size());
        var s = sessions.get(0).getAsJsonObject();
        assertEquals(10.0, s.get("startReward").getAsDouble());
        assertEquals(12.0, s.get("endReward").getAsDouble());
        assertEquals("Moved away from the zone", s.get("endReason").getAsString());
        // changes inside one sample window: the first one stays, the last one follows the counter
        var samples = history.get("i").getAsJsonArray("samples");
        assertEquals(2, samples.size());
        assertEquals(10.0, samples.get(0).getAsJsonArray().get(1).getAsDouble());
        assertEquals(12.0, samples.get(1).getAsJsonArray().get(1).getAsDouble());
        assertEquals(t + 120_000, samples.get(1).getAsJsonArray().get(0).getAsLong());
    }

    @Test
    void anOpenStayEndsWhenTheManagerRestarts() {
        long t = 1_000_000;
        new AfkHistory(dir).record("i", event("IN_ZONE", t, 5.0, t));
        var s = new AfkHistory(dir).get("i").getAsJsonArray("sessions").get(0).getAsJsonObject();
        assertEquals("ZenithHub restarted", s.get("endReason").getAsString());
        assertFalse(s.get("end").getAsLong() < t);
    }

    @Test
    void gainedCountsOnlyRisesSinceTheStart() {
        var history = new AfkHistory(dir);
        long t = 1_000_000_000L;
        long min = 60_000;
        history.record("i", event("IN_ZONE", t, 10.0, t));
        history.record("i", event("IN_ZONE", t + 6 * min, 20.0, t));
        // spent 5 in between
        history.record("i", event("IN_ZONE", t + 12 * min, 15.0, t));
        history.record("i", event("IN_ZONE", t + 18 * min, 25.0, t));
        assertEquals(20.0, history.gainedSince("i", t + min));
        assertEquals(10.0, history.gainedSince("i", t + 13 * min));
        assertEquals(0.0, history.gainedSince("i", t + 60 * min));
        assertNull(history.gainedSince("other", t));
    }
}
