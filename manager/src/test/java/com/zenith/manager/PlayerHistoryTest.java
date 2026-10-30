package com.zenith.manager;

import com.google.gson.JsonObject;
import com.zenith.manager.instances.PlayerHistory;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

class PlayerHistoryTest {
    @TempDir Path dir;

    private static JsonObject event(String event, String name, boolean spectator, long ts, String reason) {
        var json = new JsonObject();
        json.addProperty("event", event);
        json.addProperty("name", name);
        json.addProperty("spectator", spectator);
        json.addProperty("ts", ts);
        if (reason != null) json.addProperty("reason", reason);
        return json;
    }

    @Test
    void swapBecomesOneSwitchedEntry() {
        var history = new PlayerHistory(dir);
        long now = System.currentTimeMillis();
        history.record("i", event("joined", "Alex", false, now, null));
        history.noteAction("i", "swap", null, "admin");
        history.record("i", event("left", "Alex", false, now + 100, "Transferring to localhost:25565"));
        history.record("i", event("joined", "Alex", true, now + 2000, null));
        var list = history.list("i");
        assertEquals(2, list.size());
        var switched = list.get(0).getAsJsonObject();
        assertEquals("switched", switched.get("event").getAsString());
        assertEquals("admin", switched.get("actor").getAsString());
        assertEquals(true, switched.get("spectator").getAsBoolean());
    }

    @Test
    void quickRelogStaysLeftAndJoined() {
        var history = new PlayerHistory(dir);
        long now = System.currentTimeMillis();
        history.record("i", event("joined", "Alex", false, now, null));
        history.record("i", event("left", "Alex", false, now + 100, "Timed out"));
        history.record("i", event("joined", "Alex", false, now + 3000, null));
        var list = history.list("i");
        assertEquals(3, list.size());
        assertEquals("joined", list.get(0).getAsJsonObject().get("event").getAsString());
        assertEquals("Timed out", list.get(1).getAsJsonObject().get("reason").getAsString());
    }

    @Test
    void failedKickNamesNobody() {
        var history = new PlayerHistory(dir);
        long now = System.currentTimeMillis();
        history.record("i", event("joined", "Herobrine", true, now, null));
        history.noteAction("i", "kick", "Herobrine", "admin");
        history.cancelAction("i", "kick", "Herobrine", "admin");
        history.record("i", event("left", "Herobrine", true, now + 50, null));
        assertEquals("left", history.list("i").get(0).getAsJsonObject().get("event").getAsString());
    }

    @Test
    void kicksNameWhoDidIt() {
        var history = new PlayerHistory(dir);
        long now = System.currentTimeMillis();
        history.record("i", event("joined", "Herobrine", true, now, null));
        history.noteAction("i", "kick", "Herobrine", "admin");
        history.record("i", event("left", "Herobrine", true, now + 50, null));
        var kicked = history.list("i").get(0).getAsJsonObject();
        assertEquals("kicked", kicked.get("event").getAsString());
        assertEquals("admin", kicked.get("actor").getAsString());
    }

    @Test
    void keepsHistoryAcrossRestarts() {
        long now = System.currentTimeMillis();
        new PlayerHistory(dir).record("i", event("left", "Alex", false, now, "Disconnected"));
        var entry = new PlayerHistory(dir).list("i").get(0).getAsJsonObject();
        assertEquals("left", entry.get("event").getAsString());
        assertEquals("Disconnected", entry.get("reason").getAsString());
        var history = new PlayerHistory(dir);
        history.forget("i");
        assertFalse(history.list("i").size() > 0);
    }
}
