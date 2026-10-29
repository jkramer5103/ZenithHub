package com.zenith.manager;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.zenith.manager.instances.ConfigComposer;
import com.zenith.manager.state.State;
import com.zenith.manager.util.Json;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ConfigComposerTest {
    private static JsonObject json(String s) {
        return JsonParser.parseString(s).getAsJsonObject();
    }

    private static final JsonObject DEFAULTS = json("""
        {
          "client": {"autoConnect": false, "server": {"address": "2b2t.org", "port": 25565},
                     "connectionProxy": {"enabled": false, "host": "127.0.0.1"},
                     "extra": {"autoEat": {"enabled": true, "hunger": 10}, "antiafk": {"enabled": true}}},
          "server": {"bind": {"address": "0.0.0.0", "port": 25565}, "upnp": false, "proxyIP": "localhost"},
          "authentication": {"accountType": "device_code", "username": "Unknown", "openBrowserOnLogin": true},
          "discord": {"enable": false},
          "interactiveTerminal": {"enable": true, "logToDiscord": true}
        }
        """);

    private static ConfigComposer.Context ctx(JsonObject global, JsonObject serverSettings, JsonObject instanceSettings, boolean proxies) {
        var instance = new State.Instance();
        instance.id = "i1";
        instance.mcPort = 30001;
        instance.settings = instanceSettings;
        var server = new State.ServerProfile();
        server.address = "constantiam.net";
        server.port = 25566;
        server.settings = serverSettings;
        var account = new State.Account();
        account.type = State.AccountType.MSA;
        account.username = "Steve";
        var settings = new State.ManagerSettings();
        settings.publicAddress = "proxy.example.com";
        settings.outboundProxiesEnabled = proxies;
        return new ConfigComposer.Context(instance, server, account, global, settings);
    }

    @Test
    void layersApplyInOrderAndForcedValuesWin() {
        var c = ctx(
            json("{\"client\": {\"extra\": {\"autoEat\": {\"hunger\": 12}}}}"),
            json("{\"client\": {\"extra\": {\"autoEat\": {\"hunger\": 14}, \"antiafk\": {\"enabled\": false}}}}"),
            json("{\"client\": {\"extra\": {\"autoEat\": {\"hunger\": 16}}}, \"server\": {\"bind\": {\"port\": 1}}}"),
            false);
        var config = ConfigComposer.compose(DEFAULTS, c);
        var autoEat = config.getAsJsonObject("client").getAsJsonObject("extra").getAsJsonObject("autoEat");
        assertEquals(16, autoEat.get("hunger").getAsInt());
        assertTrue(autoEat.get("enabled").getAsBoolean());
        assertFalse(config.getAsJsonObject("client").getAsJsonObject("extra").getAsJsonObject("antiafk").get("enabled").getAsBoolean());
        // instance can't override the bind port
        assertEquals(30001, config.getAsJsonObject("server").getAsJsonObject("bind").get("port").getAsInt());
        assertEquals("127.0.0.1", config.getAsJsonObject("server").getAsJsonObject("bind").get("address").getAsString());
        assertEquals("constantiam.net", config.getAsJsonObject("client").getAsJsonObject("server").get("address").getAsString());
        assertEquals("Steve", config.getAsJsonObject("authentication").get("username").getAsString());
        assertFalse(config.getAsJsonObject("interactiveTerminal").get("enable").getAsBoolean());
    }

    @Test
    void inGameChangesBecomeInstanceOverrides() {
        var c = ctx(new JsonObject(), json("{\"client\": {\"extra\": {\"antiafk\": {\"enabled\": false}}}}"), new JsonObject(), false);
        var saved = ConfigComposer.compose(DEFAULTS, c);
        // user changed hunger in-game; Zenith also rewrote a forced value
        saved.getAsJsonObject("client").getAsJsonObject("extra").getAsJsonObject("autoEat").addProperty("hunger", 18);
        saved.getAsJsonObject("authentication").addProperty("username", "Renamed");
        var overrides = ConfigComposer.extractInstanceOverrides(DEFAULTS, c, saved);
        assertEquals(json("{\"client\": {\"extra\": {\"autoEat\": {\"hunger\": 18}}}}"), overrides);
    }

    @Test
    void forcedPathsKeepPreviousInstanceOverride() {
        // proxy enabled per-instance, but the admin switch is off: the override must survive a run
        var c = ctx(new JsonObject(), new JsonObject(), json("{\"client\": {\"connectionProxy\": {\"enabled\": true, \"host\": \"10.0.0.5\"}}}"), false);
        var saved = ConfigComposer.compose(DEFAULTS, c);
        assertFalse(saved.getAsJsonObject("client").getAsJsonObject("connectionProxy").get("enabled").getAsBoolean());
        var overrides = ConfigComposer.extractInstanceOverrides(DEFAULTS, c, saved);
        assertEquals(c.instance().settings, overrides);
    }

    @Test
    void diffRoundTrips() {
        var base = json("{\"a\": {\"b\": 1, \"c\": [1, 2]}, \"d\": \"x\"}");
        var actual = json("{\"a\": {\"b\": 1, \"c\": [1, 3]}, \"d\": \"y\", \"e\": true}");
        var diff = Json.diff(base, actual);
        assertEquals(json("{\"a\": {\"c\": [1, 3]}, \"d\": \"y\", \"e\": true}"), diff);
        assertEquals(actual, Json.merge(base, diff));
    }
}
