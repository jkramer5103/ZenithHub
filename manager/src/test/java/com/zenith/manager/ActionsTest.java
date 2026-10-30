package com.zenith.manager;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.zenith.manager.auth.Capability;
import com.zenith.manager.instances.Actions;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class ActionsTest {
    private static JsonObject json(String s) {
        return JsonParser.parseString(s).getAsJsonObject();
    }

    private static String command(String s) {
        return Actions.parse(json(s)).command();
    }

    @Test
    void buildsCommands() {
        assertEquals("pathfinder goto 100 -20", command("{\"action\":\"goto\",\"x\":100,\"z\":-20}"));
        assertEquals("pathfinder goto 1 64 2", command("{\"action\":\"goto\",\"x\":1,\"y\":64,\"z\":2}"));
        assertEquals("pathfinder mine diamond_ore", command("{\"action\":\"mine\",\"block\":\"minecraft:Diamond Ore\"}"));
        assertEquals("inventory drop stack 36", command("{\"action\":\"drop\",\"slot\":36,\"stack\":true}"));
        assertEquals("rotate 90.00 -45.50", command("{\"action\":\"rotate\",\"yaw\":90,\"pitch\":-45.5}"));
        assertEquals("pathfinder near 1 2 3 25", command("{\"action\":\"near\",\"x\":1,\"y\":2,\"z\":3,\"range\":5}"));
        assertEquals("pathfinder click right 1 2 3", command("{\"action\":\"clickBlock\",\"button\":\"right\",\"x\":1,\"y\":2,\"z\":3}"));
        assertEquals("pathfinder click left entity zombie", command("{\"action\":\"clickEntity\",\"button\":\"left\",\"entity\":\"zombie\"}"));
        assertEquals("pathfinder clearArea 0 60 0 4 64 4", command("{\"action\":\"clearArea\",\"from\":{\"x\":0,\"y\":60,\"z\":0},\"to\":{\"x\":4,\"y\":64,\"z\":4}}"));
        assertEquals("waypoints add home overworld 1 2 3", command("{\"action\":\"addWaypoint\",\"id\":\"home\",\"dimension\":\"minecraft:overworld\",\"x\":1,\"y\":2,\"z\":3}"));
        assertEquals("transfer play.example.net 25566 Steve", command("{\"action\":\"transfer\",\"address\":\"play.example.net\",\"port\":25566,\"player\":\"Steve\"}"));
        assertEquals("controller set Alex", command("{\"action\":\"makePlayer\",\"player\":\"Alex\"}"));
        assertEquals("controller release", command("{\"action\":\"makeSpectator\"}"));
        assertEquals("afkZone keep on", command("{\"action\":\"afkKeep\",\"keep\":true}"));
        assertEquals("afkZone keep off", command("{\"action\":\"afkKeep\",\"keep\":false}"));
        assertEquals("afkZone enter", command("{\"action\":\"afkEnter\"}"));
        assertEquals("afkZone leave", command("{\"action\":\"afkLeave\"}"));
    }

    @Test
    void capabilities() {
        assertEquals(Capability.PLAY, Actions.parse(json("{\"action\":\"stop\"}")).capability());
        assertEquals(Capability.CONTROL, Actions.parse(json("{\"action\":\"kick\"}")).capability());
        assertEquals(Capability.PEARLS, Actions.parse(json("{\"action\":\"loadPearl\",\"pearl\":\"Steve\"}")).capability());
        assertEquals(Capability.CONTROL, Actions.parse(json("{\"action\":\"afkKeep\",\"keep\":true}")).capability());
        assertEquals(Capability.PLAY, Actions.parse(json("{\"action\":\"afkEnter\"}")).capability());
        assertEquals(Capability.COMMANDS, Actions.parse(json("{\"action\":\"command\",\"command\":\"help\"}")).capability());
    }

    @Test
    void tasksWrapAnotherAction() {
        var task = Actions.parse(json("{\"action\":\"addTask\",\"id\":\"pearl\",\"when\":\"after\",\"delaySeconds\":900,\"run\":{\"action\":\"loadPearl\",\"pearl\":\"Steve\"}}"));
        assertEquals("tasks add timed pearl 900s pearlLoader load Steve", task.command());
        assertEquals(Capability.CONTROL, task.capability());
        assertEquals(Capability.PEARLS, task.alsoNeeds());
        assertEquals("tasks add event t1 online once pathfinder goto 0 1500",
            command("{\"action\":\"addTask\",\"id\":\"t1\",\"when\":\"event\",\"event\":\"online\",\"run\":{\"action\":\"goto\",\"x\":0,\"z\":1500}}"));
        assertThrows(IllegalArgumentException.class, () -> command("{\"action\":\"addTask\",\"id\":\"t\",\"when\":\"after\",\"delaySeconds\":5,\"run\":{\"action\":\"deleteTask\",\"id\":\"x\"}}"));
    }

    @Test
    void rejectsInjection() {
        assertThrows(IllegalArgumentException.class, () -> command("{\"action\":\"follow\",\"player\":\"Steve stop\"}"));
        assertThrows(IllegalArgumentException.class, () -> command("{\"action\":\"loadPearl\",\"pearl\":\"a b\"}"));
        assertThrows(IllegalArgumentException.class, () -> command("{\"action\":\"mine\",\"block\":\"stone; kick\"}"));
        assertThrows(IllegalArgumentException.class, () -> command("{\"action\":\"goto\",\"x\":1.5,\"z\":2}"));
        assertThrows(IllegalArgumentException.class, () -> command("{\"action\":\"transfer\",\"address\":\"a b\"}"));
        assertThrows(IllegalArgumentException.class, () -> command("{\"action\":\"makePlayer\",\"player\":\"Alex release\"}"));
        assertThrows(IllegalArgumentException.class, () -> command("{\"action\":\"nope\"}"));
    }
}
