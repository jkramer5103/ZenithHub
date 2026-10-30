package com.zenith.manager;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.zenith.manager.instances.ConfigComposer;
import com.zenith.manager.state.State;
import com.zenith.manager.util.Json;
import org.junit.jupiter.api.Test;

import java.util.Set;

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
          "authentication": {"accountType": "device_code", "username": "Unknown", "openBrowserOnLogin": true}
        }
        """);

    private static ConfigComposer.Context ctx(JsonObject global, JsonObject serverSettings, JsonObject instanceSettings, boolean proxies) {
        return ctx(global, serverSettings, instanceSettings, proxies, false);
    }

    private static ConfigComposer.Context ctx(JsonObject global, JsonObject serverSettings, JsonObject instanceSettings, boolean proxies, boolean cheatsDisabled) {
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
        settings.userProxiesAllowed = proxies;
        return new ConfigComposer.Context(instance, server, account, global, settings, ConfigComposer.JoinLists.EMPTY, cheatsDisabled);
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
        assertFalse(config.getAsJsonObject("authentication").get("openBrowserOnLogin").getAsBoolean());
    }

    @Test
    void inGameChangesBecomeInstanceOverrides() {
        var c = ctx(new JsonObject(), json("{\"client\": {\"extra\": {\"antiafk\": {\"enabled\": false}}}}"), new JsonObject(), false);
        var saved = ConfigComposer.compose(DEFAULTS, c);
        // user changed hunger in-game; Zenith also rewrote a forced value
        saved.getAsJsonObject("client").getAsJsonObject("extra").getAsJsonObject("autoEat").addProperty("hunger", 18);
        saved.getAsJsonObject("authentication").addProperty("username", "Renamed");
        var overrides = ConfigComposer.extractInstanceOverrides(DEFAULTS, c, saved, Set.of());
        assertEquals(json("{\"client\": {\"extra\": {\"autoEat\": {\"hunger\": 18}}}}"), overrides);
    }

    @Test
    void globalChangeThatNeverReachedTheInstanceIsNotAnOverride() {
        // the instance started with hunger 10; the admin set 14 globally while it was starting
        var saved = ConfigComposer.compose(DEFAULTS, ctx(new JsonObject(), new JsonObject(), new JsonObject(), false));
        var c = ctx(json("{\"client\": {\"extra\": {\"autoEat\": {\"hunger\": 14}}}}"), new JsonObject(), new JsonObject(), false);
        assertEquals(new JsonObject(), ConfigComposer.extractInstanceOverrides(DEFAULTS, c, saved, Set.of("client.extra.autoEat.hunger")));
        // without knowing, the old value would stick as an instance override
        assertEquals(json("{\"client\": {\"extra\": {\"autoEat\": {\"hunger\": 10}}}}"), ConfigComposer.extractInstanceOverrides(DEFAULTS, c, saved, Set.of()));
    }

    private static final JsonObject GLOBAL_PROXY = json("""
        {"client": {"connectionProxy": {"enabled": true, "type": "SOCKS5", "host": "gw.example.com", "port": 1080, "user": "admin", "password": "secret"}}}
        """);

    private static JsonObject proxy(JsonObject config) {
        return config.getAsJsonObject("client").getAsJsonObject("connectionProxy");
    }

    @Test
    void withoutOwnProxiesEveryInstanceUsesTheGlobalProxy() {
        var c = ctx(GLOBAL_PROXY, json("{\"client\": {\"connectionProxy\": {\"port\": 9000}}}"),
            json("{\"client\": {\"connectionProxy\": {\"enabled\": false, \"host\": \"10.0.0.5\"}}}"), false);
        assertEquals(proxy(GLOBAL_PROXY), proxy(ConfigComposer.compose(DEFAULTS, c)));
    }

    @Test
    void ownProxyCanChangeButNotTurnOffTheGlobalOne() {
        var c = ctx(GLOBAL_PROXY, new JsonObject(), json("{\"client\": {\"connectionProxy\": {\"enabled\": false, \"host\": \"10.0.0.5\"}}}"), true);
        var saved = ConfigComposer.compose(DEFAULTS, c);
        var p = proxy(saved);
        assertTrue(p.get("enabled").getAsBoolean());
        assertEquals("10.0.0.5", p.get("host").getAsString());
        // the admin's login never goes to someone else's proxy
        assertEquals("", p.get("user").getAsString());
        assertEquals("", p.get("password").getAsString());
        // and the blanked login isn't mistaken for the user's own
        assertEquals(c.instance().settings, ConfigComposer.extractInstanceOverrides(DEFAULTS, c, saved, Set.of()));
    }

    @Test
    void ownProxySettingsKeepTheGlobalLoginForTheSameProxy() {
        var c = ctx(GLOBAL_PROXY, new JsonObject(), json("{\"client\": {\"connectionProxy\": {\"user\": \"admin-session-2\"}}}"), true);
        var p = proxy(ConfigComposer.compose(DEFAULTS, c));
        assertEquals("admin-session-2", p.get("user").getAsString());
        assertEquals("secret", p.get("password").getAsString());
    }

    @Test
    void oldProxySwitchStillLoads() {
        var state = Json.GSON.fromJson("{\"settings\": {\"outboundProxiesEnabled\": true}}", State.class);
        assertTrue(state.settings.userProxiesAllowed);
    }

    @Test
    void forcedPathsKeepPreviousInstanceOverride() {
        // proxy enabled per-instance, but own proxies aren't allowed: the override must survive a run
        var c = ctx(new JsonObject(), new JsonObject(), json("{\"client\": {\"connectionProxy\": {\"enabled\": true, \"host\": \"10.0.0.5\"}}}"), false);
        var saved = ConfigComposer.compose(DEFAULTS, c);
        assertFalse(saved.getAsJsonObject("client").getAsJsonObject("connectionProxy").get("enabled").getAsBoolean());
        var overrides = ConfigComposer.extractInstanceOverrides(DEFAULTS, c, saved, Set.of());
        assertEquals(c.instance().settings, overrides);
    }

    @Test
    void disabledCheatsAreForcedOffButKeepTheirOverrides() {
        var c = ctx(new JsonObject(), new JsonObject(), json("{\"client\": {\"extra\": {\"autoEat\": {\"enabled\": true, \"hunger\": 16}}}}"), false, true);
        var saved = ConfigComposer.compose(DEFAULTS, c);
        var extra = saved.getAsJsonObject("client").getAsJsonObject("extra");
        assertFalse(extra.getAsJsonObject("autoEat").get("enabled").getAsBoolean());
        assertFalse(extra.getAsJsonObject("antiafk").get("enabled").getAsBoolean());
        assertEquals(1.0, saved.getAsJsonObject("client").get("tickRate").getAsDouble());
        // turning the switch off again brings back what the instance had
        assertEquals(c.instance().settings, ConfigComposer.extractInstanceOverrides(DEFAULTS, c, saved, Set.of()));
        var normal = ConfigComposer.compose(DEFAULTS, ctx(new JsonObject(), new JsonObject(), c.instance().settings, false));
        assertTrue(normal.getAsJsonObject("client").getAsJsonObject("extra").getAsJsonObject("autoEat").get("enabled").getAsBoolean());
    }

    @Test
    void alwaysForcedMatchesForcedValues() {
        var minimal = ctx(new JsonObject(), new JsonObject(), new JsonObject(), true);
        assertEquals(ConfigComposer.ALWAYS_FORCED, ConfigComposer.forcedValues(minimal).keySet());
        var offline = ctx(new JsonObject(), new JsonObject(), new JsonObject(), false, true);
        offline.account().type = State.AccountType.OFFLINE;
        assertTrue(ConfigComposer.forcedValues(offline).keySet().containsAll(ConfigComposer.ALWAYS_FORCED));
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
