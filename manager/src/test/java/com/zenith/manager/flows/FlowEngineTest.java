package com.zenith.manager.flows;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.zenith.manager.EventHub;
import com.zenith.manager.auth.Capability;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.LocalTime;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.junit.jupiter.api.Assertions.*;

class FlowEngineTest {
    @TempDir Path dir;

    static final class FakeBot implements FlowEngine.Bot {
        final List<String> commands = new CopyOnWriteArrayList<>();
        volatile Set<Capability> caps = EnumSet.allOf(Capability.class);
        volatile JsonObject status = JsonParser.parseString("{\"online\":true,\"health\":20,\"controlled\":false}").getAsJsonObject();

        @Override public JsonObject command(String instanceId, String command, String actor) {
            commands.add(command);
            return new JsonObject();
        }

        @Override public void loadPearl(String instanceId, String pearl, String actor) {
            commands.add("pearl " + pearl);
        }

        @Override public void disconnect(String instanceId, String actor) {
            commands.add("disconnect");
        }

        @Override public void connect(String instanceId, String actor) {
            commands.add("connect");
        }

        @Override public JsonObject status(String instanceId) {
            return status;
        }

        volatile JsonObject details = json("{'inventory':{'slots':[{'id':'minecraft:totem_of_undying','count':1},{'id':'minecraft:totem_of_undying','count':1}]}}");

        @Override public JsonObject details(String instanceId) {
            return details;
        }

        @Override public String server(String instanceId) {
            return "Test SMP";
        }

        @Override public Set<Capability> capabilities(String instanceId, String userId) {
            return caps;
        }
    }

    static JsonObject json(String s) {
        return JsonParser.parseString(s.replace('\'', '"')).getAsJsonObject();
    }

    static void await(FakeBot bot, int count) throws InterruptedException {
        for (int i = 0; i < 200 && bot.commands.size() < count; i++) Thread.sleep(10);
    }

    @Test void greetsStrangersButNotFriends() throws Exception {
        var bot = new FakeBot();
        var hub = new EventHub();
        var engine = new FlowEngine(dir, bot, hub);
        engine.save("i1", json("""
            {'id':'greet','name':'Greet','enabled':true,'cooldown':3,'trigger':{'kind':'player','who':'anyone'},
             'steps':[{'id':'a','type':'if','cond':{'kind':'friend','not':true},
                       'then':[{'id':'b','type':'say','text':'Hi {player}!'}],
                       'else':[{'id':'c','type':'notify','text':'{player} is here'}]}]}"""), "u1", "alice");
        hub.publish("game", "i1", json("{'event':'visualRange','action':'enter','player':'Steve','friend':false}"));
        await(bot, 1);
        assertEquals(List.of("say Hi Steve!"), bot.commands);
    }

    @Test void chatFromOthersCantBecomeACommand() throws Exception {
        var bot = new FakeBot();
        var hub = new EventHub();
        var engine = new FlowEngine(dir, bot, hub);
        engine.save("i1", json("""
            {'id':'echo','name':'Echo','enabled':true,'trigger':{'kind':'chat','contains':''},
             'steps':[{'id':'a','type':'say','text':'{message}'},{'id':'b','type':'notify','text':'after'}]}"""), "u1", "alice");
        hub.publish("game", "i1", json("{'event':'chat','from':'Bob','message':'/op Bob'}"));
        Thread.sleep(200);
        assertTrue(bot.commands.isEmpty(), bot.commands.toString());
        var flow = engine.list("i1").get(0).getAsJsonObject();
        assertFalse(flow.getAsJsonObject("last").get("ok").getAsBoolean());
    }

    @Test void healthTriggerFiresOnCrossingOnly() {
        var trigger = json("{'kind':'health','below':8}");
        assertTrue(FlowEngine.matches(trigger, json("{'event':'health','health':6,'previous':9}")));
        assertFalse(FlowEngine.matches(trigger, json("{'event':'health','health':5,'previous':6}")));
    }

    @Test void stopsWhenTheOwnerLosesTheCapability() throws Exception {
        var bot = new FakeBot();
        var hub = new EventHub();
        var engine = new FlowEngine(dir, bot, hub);
        engine.save("i1", json("""
            {'id':'f','name':'F','enabled':true,'trigger':{'kind':'manual'},
             'steps':[{'id':'a','type':'action','run':{'action':'respawn'}}]}"""), "u1", "alice");
        bot.caps = EnumSet.of(Capability.VIEW, Capability.CONTROL);
        engine.runNow("i1", "f");
        Thread.sleep(200);
        assertTrue(bot.commands.isEmpty());
        assertTrue(engine.list("i1").get(0).getAsJsonObject().getAsJsonObject("last").get("message").getAsString().contains("play"));
    }

    @Test void savingNeedsTheCapabilitiesOfEveryBlock() {
        var bot = new FakeBot();
        bot.caps = EnumSet.of(Capability.VIEW, Capability.CONTROL);
        var engine = new FlowEngine(dir, bot, new EventHub());
        assertThrows(SecurityException.class, () -> engine.save("i1", json("""
            {'id':'f','name':'F','trigger':{'kind':'manual'},'steps':[{'id':'a','type':'say','text':'hi'}]}"""), "u1", "alice"));
        assertThrows(IllegalArgumentException.class, () -> engine.save("i1", json("""
            {'id':'f','name':'F','trigger':{'kind':'manual'},'steps':[{'id':'a','type':'action','run':{'action':'command','command':'op x'}}]}"""), "u1", "alice"));
    }

    @Test void repeatAndWaitForEvents() throws Exception {
        var bot = new FakeBot();
        var hub = new EventHub();
        var engine = new FlowEngine(dir, bot, hub);
        engine.save("i1", json("""
            {'id':'f','name':'F','trigger':{'kind':'manual'},
             'steps':[{'id':'a','type':'repeat','times':2,'steps':[{'id':'b','type':'action','run':{'action':'respawn'}}]},
                      {'id':'c','type':'waitFor','event':{'kind':'whisper','contains':'go'},'timeout':5},
                      {'id':'d','type':'say','text':'ok {player}'}]}"""), "u1", "alice");
        engine.runNow("i1", "f");
        await(bot, 2);
        hub.publish("game", "i1", json("{'event':'whisper','from':'Ann','message':'go now'}"));
        await(bot, 3);
        assertEquals(List.of("respawn", "respawn", "say ok Ann"), bot.commands);
        // kept on disk
        assertEquals(1, new FlowEngine(dir, bot, new EventHub()).list("i1").size());
    }

    static JsonObject runtime(String phase, String status) {
        var json = json("{'phase':'" + phase + "'}");
        json.add("status", json(status));
        return json;
    }

    @Test void statusChangesBecomeTriggers() {
        var engine = new FlowEngine(dir, new FakeBot(), new EventHub());
        assertTrue(engine.changes("i1", runtime("RUNNING", "{'online':false}")).isEmpty());
        var joined = engine.changes("i1", runtime("RUNNING", "{'online':true,'inQueue':true,'queuePosition':40,'food':20,'afkZone':{'state':'IN_ZONE','earning':true}}"));
        assertTrue(joined.stream().anyMatch(e -> e.get("event").getAsString().equals("joined")), joined.toString());
        var trigger = json("{'kind':'queueBelow','position':10}");
        var down = engine.changes("i1", runtime("RUNNING", "{'online':true,'inQueue':true,'queuePosition':9,'food':5,'afkZone':{'state':'IN_ZONE','earning':false}}"));
        assertTrue(down.stream().anyMatch(e -> FlowEngine.matches(trigger, e)), down.toString());
        assertTrue(down.stream().anyMatch(e -> FlowEngine.matches(json("{'kind':'afk','on':'earningStopped'}"), e)));
        assertTrue(down.stream().anyMatch(e -> FlowEngine.matches(json("{'kind':'food','below':6}"), e)));
        var again = engine.changes("i1", runtime("RUNNING", "{'online':true,'inQueue':true,'queuePosition':8,'food':5,'afkZone':{'state':'IN_ZONE','earning':false}}"));
        assertFalse(again.stream().anyMatch(e -> FlowEngine.matches(trigger, e)), "only when it crosses");
        var left = engine.changes("i1", runtime("BACKOFF", "{'online':false,'lastDisconnectReason':'You are banned'}"));
        assertTrue(left.stream().anyMatch(e -> FlowEngine.matches(json("{'kind':'left','reason':'banned'}"), e)));
        assertTrue(left.stream().anyMatch(e -> FlowEngine.matches(json("{'kind':'crashed'}"), e)));
    }

    @Test void dailyFindsTheNextMatchingDay() {
        var zone = ZoneId.of("Europe/Berlin");
        // Saturday 2026-11-21 10:00
        long now = ZonedDateTime.of(2026, 11, 21, 10, 0, 0, 0, zone).toInstant().toEpochMilli();
        var weekdays = json("{'kind':'daily','at':'08:30','days':'12345','zone':'Europe/Berlin'}");
        assertEquals(ZonedDateTime.of(2026, 11, 23, 8, 30, 0, 0, zone).toInstant().toEpochMilli(), FlowEngine.nextDaily(weekdays, now));
        var later = json("{'kind':'daily','at':'18:00','days':'1234567','zone':'Europe/Berlin'}");
        assertEquals(ZonedDateTime.of(2026, 11, 21, 18, 0, 0, 0, zone).toInstant().toEpochMilli(), FlowEngine.nextDaily(later, now));
        assertTrue(FlowEngine.between(LocalTime.of(23, 0), LocalTime.of(22, 0), LocalTime.of(6, 0)));
        assertTrue(FlowEngine.between(LocalTime.of(5, 59), LocalTime.of(22, 0), LocalTime.of(6, 0)));
        assertFalse(FlowEngine.between(LocalTime.of(12, 0), LocalTime.of(22, 0), LocalTime.of(6, 0)));
    }

    @Test void variablesCountAcrossRunsAndFillTemplates() throws Exception {
        var bot = new FakeBot();
        var engine = new FlowEngine(dir, bot, new EventHub());
        engine.save("i1", json("""
            {'id':'f','name':'F','trigger':{'kind':'manual'},
             'steps':[{'id':'a','type':'change','name':'deaths','by':1},
                      {'id':'b','type':'if','cond':{'kind':'var','name':'deaths','op':'>','value':'1'},
                       'then':[{'id':'c','type':'notify','text':'{deaths} deaths on {server}, {health} hp'}],'else':[]}]}"""), "u1", "alice");
        engine.runNow("i1", "f");
        Thread.sleep(150);
        assertTrue(bot.commands.isEmpty());
        engine.runNow("i1", "f");
        await(bot, 1);
        assertEquals(List.of("notify 2 deaths on Test SMP, 20 hp"), bot.commands);
        Thread.sleep(100);
        var reloaded = new FlowEngine(dir, bot, new EventHub()).activity("i1", "f");
        assertEquals("2", reloaded.getAsJsonObject("variables").get("deaths").getAsString());
        assertEquals(2, reloaded.getAsJsonArray("runs").size());
    }

    @Test void whisperOnlyGoesToRealNames() throws Exception {
        var bot = new FakeBot();
        var hub = new EventHub();
        var engine = new FlowEngine(dir, bot, hub);
        engine.save("i1", json("""
            {'id':'f','name':'F','enabled':true,'cooldown':0,'trigger':{'kind':'chat','contains':'hi'},
             'steps':[{'id':'a','type':'whisper','player':'{message}','text':'hey'}]}"""), "u1", "alice");
        hub.publish("game", "i1", json("{'event':'chat','from':'Bob','message':'hi x; /op me'}"));
        Thread.sleep(200);
        assertTrue(bot.commands.isEmpty());
        assertTrue(engine.list("i1").get(0).getAsJsonObject().getAsJsonObject("last").get("message").getAsString().contains("Not a player"));
    }

    @Test void broadcastStartsReceivers() throws Exception {
        var bot = new FakeBot();
        var engine = new FlowEngine(dir, bot, new EventHub());
        engine.save("i1", json("""
            {'id':'a','name':'Sender','trigger':{'kind':'manual'},'steps':[{'id':'s','type':'broadcast','name':'go home'}]}"""), "u1", "alice");
        engine.save("i1", json("""
            {'id':'b','name':'Receiver','enabled':true,'trigger':{'kind':'receive','name':'Go Home'},'steps':[{'id':'r','type':'notify','text':'from {from}'}]}"""), "u1", "alice");
        engine.runNow("i1", "a");
        await(bot, 1);
        assertEquals(List.of("notify from Sender"), bot.commands);
    }

    @Test void restartModeReplacesTheRun() throws Exception {
        var bot = new FakeBot();
        var engine = new FlowEngine(dir, bot, new EventHub());
        engine.save("i1", json("""
            {'id':'f','name':'F','mode':'restart','trigger':{'kind':'manual'},
             'steps':[{'id':'a','type':'notify','text':'start'},{'id':'w','type':'wait','seconds':30},{'id':'b','type':'notify','text':'end'}]}"""), "u1", "alice");
        engine.runNow("i1", "f");
        await(bot, 1);
        engine.runNow("i1", "f");
        await(bot, 2);
        assertEquals(List.of("notify start", "notify start"), bot.commands);
        Thread.sleep(100);
        var runs = engine.activity("i1", "f").getAsJsonArray("runs");
        assertEquals("Stopped", runs.get(0).getAsJsonObject().get("message").getAsString());
        engine.stop("i1", "f");
    }

    @Test void conditionsReadTheBot() throws Exception {
        var bot = new FakeBot();
        bot.status = json("{'online':true,'health':20,'position':{'x':100.5,'y':64,'z':-20,'dimension':'minecraft:the_nether'}}");
        var engine = new FlowEngine(dir, bot, new EventHub());
        engine.save("i1", json("""
            {'id':'f','name':'F','trigger':{'kind':'manual'},
             'steps':[{'id':'a','type':'if','cond':{'kind':'near','x':90,'z':-20,'radius':16},'then':[{'id':'b','type':'notify','text':'near'}],'else':[]},
                      {'id':'c','type':'if','cond':{'kind':'dimension','is':'the_nether'},'then':[{'id':'d','type':'notify','text':'nether {x} {z}'}],'else':[]},
                      {'id':'e','type':'if','cond':{'kind':'hasItem','item':'minecraft:totem_of_undying','count':3},'then':[],'else':[{'id':'g','type':'notify','text':'few totems'}]}]}"""), "u1", "alice");
        engine.runNow("i1", "f");
        await(bot, 3);
        assertEquals(List.of("notify near", "notify nether 100 -20", "notify few totems"), bot.commands);
    }

    @Test void errorsPointAtTheBlock() {
        var engine = new FlowEngine(dir, new FakeBot(), new EventHub());
        var e = assertThrows(FlowSpec.Invalid.class, () -> engine.save("i1", json("""
            {'id':'f','name':'F','trigger':{'kind':'manual'},
             'steps':[{'id':'ok','type':'notify','text':'x'},{'id':'loop','type':'repeat','times':2,'steps':[{'id':'bad','type':'wait','seconds':-1}]}]}"""), "u1", "alice"));
        assertEquals("bad", e.step);
    }

    @Test void chatIsRateLimited() throws Exception {
        var bot = new FakeBot();
        var engine = new FlowEngine(dir, bot, new EventHub());
        engine.save("i1", json("""
            {'id':'f','name':'F','trigger':{'kind':'manual'},'steps':[{'id':'r','type':'repeat','times':10,'steps':[{'id':'s','type':'say','text':'spam'}]}]}"""), "u1", "alice");
        engine.runNow("i1", "f");
        Thread.sleep(1500);
        assertEquals(FlowEngine.CHAT_LIMIT, bot.commands.size());
        engine.stop("i1", "f");
    }
}
