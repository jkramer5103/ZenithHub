package com.zenith.manager;

import com.google.gson.JsonObject;
import com.zenith.manager.instances.ConfigComposer;
import com.zenith.manager.instances.ProxyPool;
import com.zenith.manager.state.State;
import com.zenith.manager.state.StateStore;
import com.zenith.manager.util.Json;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ProxyPoolTest {
    @TempDir Path dir;

    private ProxyPool pool(String list, String... accounts) throws Exception {
        Files.writeString(dir.resolve("proxy-pool.txt"), list);
        var store = new StateStore(dir.resolve("state.json"));
        store.update(s -> {
            for (var id : accounts) {
                var a = new State.Account();
                a.id = id;
                a.username = id;
                s.accounts.add(a);
            }
            s.proxyPool.enabled = true;
        });
        return new ProxyPool(dir, store, () -> null);
    }

    @Test
    void readsCommonListFormats() throws Exception {
        var pool = pool("""
            # comment
            1.2.3.4:5000:user:pa:ss\r
            1.2.3.4:5000:dupe:dupe
            5.6.7.8:6000
            socks5://u%40x:p@9.9.9.9:7000
            garbage
            """, "a");
        var status = pool.status();
        assertEquals(3, status.get("size").getAsInt());
    }

    @Test
    void everyAccountGetsItsOwnStickyProxy() throws Exception {
        var pool = pool("1.1.1.1:1:u1:p1\n2.2.2.2:2:u2:p2\n", "a", "b", "c");
        var a = pool.forAccount("a");
        var b = pool.forAccount("b");
        assertNotEquals(a.host(), b.host());
        assertEquals(a, pool.forAccount("a"));
        // only two proxies: the third account must not share one, or connect directly
        assertTrue(pool.forAccount("c").missing());
        var hosts = new HashSet<>(java.util.List.of(a.host(), b.host()));
        assertEquals(2, hosts.size());
    }

    @Test
    void offMeansNoPoolProxy() throws Exception {
        var pool = pool("1.1.1.1:1\n", "a");
        pool.configure(false, null);
        assertNull(pool.forAccount("a"));
    }

    @Test
    void poolProxyReplacesEveryOtherProxySetting() {
        var instance = new State.Instance();
        instance.id = "i";
        instance.settings = new JsonObject();
        var server = new State.ServerProfile();
        server.address = "example.net";
        var account = new State.Account();
        account.type = State.AccountType.MSA;
        account.username = "Steve";
        var global = new JsonObject();
        Json.set(global, ConfigComposer.PROXY + "enabled", new com.google.gson.JsonPrimitive(true));
        Json.set(global, ConfigComposer.PROXY + "host", new com.google.gson.JsonPrimitive("global.example"));
        var ctx = new ConfigComposer.Context(instance, server, account, global, new State.ManagerSettings(), ConfigComposer.JoinLists.EMPTY,
            false, new ConfigComposer.PoolProxy("SOCKS5", "1.1.1.1", 1, "u", "p"));
        var config = ConfigComposer.compose(new JsonObject(), ctx);
        assertEquals("1.1.1.1", Json.get(config, ConfigComposer.PROXY + "host").getAsString());
        assertEquals("p", Json.get(config, ConfigComposer.PROXY + "password").getAsString());
        assertTrue(Json.get(config, ConfigComposer.PROXY + "enabled").getAsBoolean());
    }
}
