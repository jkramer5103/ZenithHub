package com.zenith.manager;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.JsonPrimitive;
import com.zenith.manager.instances.ConfigComposer;
import com.zenith.manager.instances.ProxyOverrides;
import com.zenith.manager.state.State;
import com.zenith.manager.util.Json;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class ProxyOverridesTest {
    @TempDir Path dir;

    private static JsonObject json(String s) {
        return JsonParser.parseString(s).getAsJsonObject();
    }

    private static JsonObject compose(String serverAddress, ConfigComposer.PoolProxy proxy) {
        var instance = new State.Instance();
        instance.id = "i1";
        instance.settings = new JsonObject();
        var server = new State.ServerProfile();
        server.address = serverAddress;
        server.port = 25565;
        server.settings = new JsonObject();
        var account = new State.Account();
        account.type = State.AccountType.MSA;
        account.username = "Steve";
        var global = new JsonObject();
        Json.set(global, ConfigComposer.PROXY + "enabled", new JsonPrimitive(true));
        Json.set(global, ConfigComposer.PROXY + "host", new JsonPrimitive("global.example"));
        var ctx = new ConfigComposer.Context(instance, server, account, global, new State.ManagerSettings(), ConfigComposer.JoinLists.EMPTY,
            false, proxy);
        return ConfigComposer.compose(new JsonObject(), ctx);
    }

    private static boolean proxied(JsonObject config) {
        return Json.get(config, ConfigComposer.PROXY + "enabled").getAsBoolean();
    }

    @Test
    void recognisesLanAddresses() {
        for (var lan : Set.of("192.168.2.10", "10.0.0.1", "172.16.5.4", "127.0.0.1", "169.254.1.1", "localhost", "minecraft", "mc.lan",
            "server.local", "box.home.arpa", "[::1]", "fd12:3456::1", "fe80::1", "MC.LAN.")) {
            assertTrue(ConfigComposer.isLanAddress(lan), lan);
        }
        for (var wan : Set.of("java.hugosmp.net", "2b2t.org", "8.8.8.8", "172.32.0.1", "100.64.0.1", "2001:db8::1", "", "300.1.1.1")) {
            assertFalse(ConfigComposer.isLanAddress(wan), wan);
        }
    }

    @Test
    void lanServersConnectDirectlyWhateverTheProxy() {
        assertFalse(proxied(compose("192.168.2.50", new ConfigComposer.PoolProxy("SOCKS5", "1.1.1.1", 1, "u", "p"))));
        assertFalse(proxied(compose("192.168.2.50", null)));
        assertTrue(proxied(compose("java.hugosmp.net", null)));
    }

    @Test
    void directOverrideTurnsTheProxyOff() {
        assertFalse(proxied(compose("java.hugosmp.net", ConfigComposer.PoolProxy.direct())));
    }

    @Test
    void savesAsASecretAndKeepsThePassword() throws Exception {
        var file = dir.resolve("secrets").resolve("proxy-overrides.json");
        var overrides = new ProxyOverrides(file);
        overrides.put("a1", overrides.parse("a1", json("{\"type\":\"socks5\",\"host\":\"p.example\",\"port\":1080,\"user\":\"u\",\"password\":\"secret\"}")));
        assertEquals("rw-------", PosixFilePermissions.toString(Files.getPosixFilePermissions(file)));
        assertFalse(overrides.view("a1").toString().contains("secret"));
        assertTrue(overrides.view("a1").get("hasPassword").getAsBoolean());
        // the form sends a blank password for "keep it"
        overrides.put("a1", overrides.parse("a1", json("{\"type\":\"SOCKS5\",\"host\":\"p.example\",\"port\":1080,\"user\":\"u\",\"password\":\"\"}")));
        var reloaded = new ProxyOverrides(file);
        assertEquals(new ConfigComposer.PoolProxy("SOCKS5", "p.example", 1080, "u", "secret"), reloaded.forAccount("a1"));
        overrides.put("a2", overrides.parse("a2", json("{\"type\":\"DIRECT\"}")));
        assertTrue(new ProxyOverrides(file).forAccount("a2").isDirect());
        overrides.clear("a1");
        assertNull(new ProxyOverrides(file).forAccount("a1"));
    }

    @Test
    void rejectsBadInput() {
        var overrides = new ProxyOverrides(dir.resolve("o.json"));
        assertThrows(IllegalArgumentException.class, () -> overrides.parse("a", json("{\"type\":\"FTP\",\"host\":\"h\",\"port\":1}")));
        assertThrows(IllegalArgumentException.class, () -> overrides.parse("a", json("{\"type\":\"HTTP\",\"host\":\"h h\",\"port\":1}")));
        assertThrows(IllegalArgumentException.class, () -> overrides.parse("a", json("{\"type\":\"HTTP\",\"host\":\"h\",\"port\":70000}")));
    }
}
