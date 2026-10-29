package com.zenith.manager.auth;

import java.util.EnumSet;
import java.util.Set;

import static com.zenith.manager.auth.Capability.*;

/**
 * Presets of capabilities, ordered from least to most access.
 */
public enum Role {
    VIEWER(EnumSet.of(VIEW)),
    SPECTATOR(EnumSet.of(VIEW, SPECTATE, PEARLS)),
    PLAYER(EnumSet.of(VIEW, SPECTATE, PEARLS, PLAY, CHAT)),
    OPERATOR(EnumSet.of(VIEW, SPECTATE, PEARLS, PLAY, CHAT, CONTROL, COMMANDS)),
    ADMIN(EnumSet.allOf(Capability.class));

    private final Set<Capability> capabilities;

    Role(Set<Capability> capabilities) {
        this.capabilities = capabilities;
    }

    public Set<Capability> capabilities() {
        return EnumSet.copyOf(capabilities);
    }
}
