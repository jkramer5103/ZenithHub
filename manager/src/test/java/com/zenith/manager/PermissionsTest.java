package com.zenith.manager;

import com.zenith.manager.auth.Capability;
import com.zenith.manager.auth.Passwords;
import com.zenith.manager.auth.Permissions;
import com.zenith.manager.auth.Role;
import com.zenith.manager.state.State;
import org.junit.jupiter.api.Test;

import java.util.EnumSet;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PermissionsTest {
    private static State.User user(String id, boolean admin, String... mcNames) {
        var u = new State.User();
        u.id = id;
        u.username = id;
        u.admin = admin;
        u.mcNames = List.of(mcNames);
        return u;
    }

    private static State.Grant grant(String subject, Role role, Capability... extra) {
        var g = new State.Grant();
        g.subject = subject;
        g.role = role;
        g.extra = extra.length == 0 ? EnumSet.noneOf(Capability.class) : EnumSet.of(extra[0], extra);
        return g;
    }

    @Test
    void adminHasEverythingEverywhere() {
        var instance = new State.Instance();
        assertEquals(EnumSet.allOf(Capability.class), Permissions.capabilities(Permissions.Identity.of(user("a", true)), instance));
    }

    @Test
    void grantsMatchAnyIdentityAndUnion() {
        var instance = new State.Instance();
        instance.grants.add(grant("user:bob", Role.VIEWER));
        instance.grants.add(grant(Permissions.mcSubject("BobMC"), Role.PLAYER));
        var bob = Permissions.Identity.of(user("bob", false, "bobmc"));
        var caps = Permissions.capabilities(bob, instance);
        assertTrue(caps.contains(Capability.PLAY));
        assertFalse(caps.contains(Capability.CONTROL));
        var stranger = Permissions.Identity.of(user("eve", false));
        assertTrue(Permissions.capabilities(stranger, instance).isEmpty());
    }

    @Test
    void canOnlyGrantUpToOwnLevel() {
        var instance = new State.Instance();
        instance.grants.add(grant("user:bob", Role.PLAYER, Capability.SHARE));
        var bob = Permissions.Identity.of(user("bob", false));
        assertTrue(Permissions.canGrant(bob, instance, Role.SPECTATOR, null));
        assertTrue(Permissions.canGrant(bob, instance, Role.PLAYER, null));
        assertFalse(Permissions.canGrant(bob, instance, Role.OPERATOR, null));
        assertFalse(Permissions.canGrant(bob, instance, Role.VIEWER, EnumSet.of(Capability.SETTINGS)));
    }

    @Test
    void passwordsVerify() {
        var hash = Passwords.hash("correct horse");
        assertTrue(Passwords.verify("correct horse", hash));
        assertFalse(Passwords.verify("wrong horse", hash));
    }
}
