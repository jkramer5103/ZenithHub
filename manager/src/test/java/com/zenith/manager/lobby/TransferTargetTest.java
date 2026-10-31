package com.zenith.manager.lobby;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class TransferTargetTest {
    @Test
    void sendsPlayersBackToTheAddressTheyJoinedWith() {
        assertEquals("192.168.2.193:25565", LobbyServer.transferTarget("192.168.2.193", 25565, "mc.example.com:41234"));
        assertEquals("mc.example.com:41234", LobbyServer.transferTarget("mc.example.com.", 41234, "localhost:25565"));
        assertEquals("mc.example.com:25565", LobbyServer.transferTarget("mc.example.com\0FML3\0", 25565, "localhost:25565"));
        assertEquals("[fd00::5]:25565", LobbyServer.transferTarget("fd00::5", 25565, "localhost:25565"));
    }

    @Test
    void fallsBackToThePublicAddressWithoutAHandshake() {
        assertEquals("mc.example.com:41234", LobbyServer.transferTarget(null, 0, "mc.example.com:41234"));
        assertEquals("mc.example.com:41234", LobbyServer.transferTarget("", 25565, "mc.example.com:41234"));
    }
}
