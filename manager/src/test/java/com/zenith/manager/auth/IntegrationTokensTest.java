package com.zenith.manager.auth;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.EnumSet;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class IntegrationTokensTest {
    @TempDir
    Path dir;

    private Path file() {
        return dir.resolve("secrets").resolve("integration-tokens.json");
    }

    @Test
    void resolvesOnlyTheRealTokenAndStoresJustAHash() throws Exception {
        var tokens = new IntegrationTokens(file());
        var created = tokens.create("alice", "Blockbot", false, Set.of("i1"), EnumSet.of(Capability.PEARLS));
        assertTrue(created.secret().startsWith(IntegrationTokens.PREFIX));
        assertEquals(created.token().id, tokens.resolve(created.secret()).id);
        assertNull(tokens.resolve(created.secret() + "x"));
        assertNull(tokens.resolve("zh_nope"));
        assertNull(tokens.resolve(null));
        assertFalse(Files.readString(file()).contains(created.secret()), "the plain token is never written");
        // survives a restart
        assertNotNull(new IntegrationTokens(file()).resolve(created.secret()));
    }

    @Test
    void scopeIsThePersonsCapabilitiesCutToTheTokens() {
        var tokens = new IntegrationTokens(file());
        var token = tokens.create("alice", "Blockbot", false, Set.of("i1"), EnumSet.of(Capability.PEARLS, Capability.CHAT)).token();
        var everything = EnumSet.allOf(Capability.class);
        assertEquals(EnumSet.of(Capability.VIEW, Capability.PEARLS, Capability.CHAT), IntegrationTokens.scope(token, "i1", everything));
        // other instances: nothing
        assertTrue(IntegrationTokens.scope(token, "i2", everything).isEmpty());
        // never more than the person has now, e.g. after a friend takes CHAT away
        assertEquals(EnumSet.of(Capability.VIEW, Capability.PEARLS), IntegrationTokens.scope(token, "i1", EnumSet.of(Capability.VIEW, Capability.PEARLS)));
        assertTrue(IntegrationTokens.scope(token, "i1", EnumSet.noneOf(Capability.class)).isEmpty());

        var all = tokens.create("alice", "All", true, Set.of(), EnumSet.of(Capability.CONTROL)).token();
        assertEquals(EnumSet.of(Capability.VIEW, Capability.CONTROL), IntegrationTokens.scope(all, "any", everything));
    }

    @Test
    void refusesBadRequests() {
        var tokens = new IntegrationTokens(file());
        assertThrows(IllegalArgumentException.class, () -> tokens.create("alice", " ", true, Set.of(), EnumSet.noneOf(Capability.class)));
        assertThrows(IllegalArgumentException.class, () -> tokens.create("alice", "x", false, Set.of(), EnumSet.noneOf(Capability.class)));
        assertThrows(IllegalArgumentException.class, () -> tokens.create("alice", "x", true, Set.of(), EnumSet.of(Capability.SHARE)));
        assertThrows(IllegalArgumentException.class, () -> tokens.create("alice", "x", true, Set.of(), EnumSet.of(Capability.SPECTATE)));
    }

    @Test
    void revokingAndForgetting() {
        var tokens = new IntegrationTokens(file());
        var a = tokens.create("alice", "One", false, Set.of("i1", "i2"), EnumSet.noneOf(Capability.class));
        var b = tokens.create("bob", "Two", true, Set.of(), EnumSet.noneOf(Capability.class));
        assertFalse(tokens.revoke("bob", a.token().id), "only your own tokens");
        tokens.forget("i1", null);
        assertEquals(Set.of("i2"), tokens.list("alice").getFirst().instances);
        tokens.forget(null, "bob");
        assertNull(tokens.resolve(b.secret()));
        assertTrue(tokens.revoke("alice", a.token().id));
        assertNull(tokens.resolve(a.secret()));
        assertTrue(tokens.list("alice").isEmpty());
    }
}
