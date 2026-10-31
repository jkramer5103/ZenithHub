package com.zenith.control;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class AfkZoneTest {
    @Test
    public void parsesSidebarAmounts() {
        assertEquals(58.0, AfkZone.parseAmount("Stars 58"));
        assertEquals(1000.0, AfkZone.parseAmount("Geld 1,00k"));
        assertEquals(1500.0, AfkZone.parseAmount("Geld 1.5k"));
        assertEquals(1234567.0, AfkZone.parseAmount("Money 1,234,567"));
        assertEquals(1234567.0, AfkZone.parseAmount("Geld 1.234.567"));
        assertEquals(2_500_000.0, AfkZone.parseAmount("Geld 2,5 Mio"));
        assertEquals(12.5, AfkZone.parseAmount("Coins 12,5"));
        assertNull(AfkZone.parseAmount("Stars"));
    }

    @Test
    public void stripsIconGlyphs() {
        assertEquals("Stars 3", ServerScreen.clean("ꐲ §eStars §f3"));
    }

    @Test
    void notEarningMatchesTheMessageInAnyLanguage() {
        var defaults = new com.zenith.util.config.Config().client.afkZone.notEarningText;
        for (var message : new String[] {
            "Du musst dich auf Discord verifizieren: hugosmp.de/discord",
            "Tu dois te vérifier sur Discord : hugosmp.de/discord",
            "You have to verify yourself on Discord: hugosmp.de/discord" }) {
            assertTrue(AfkZone.containsAny(message.toLowerCase(java.util.Locale.ROOT), defaults), message);
        }
        assertFalse(AfkZone.containsAny("+1 star in 42 sekunden", defaults));
        assertFalse(AfkZone.containsAny("anything", ""));
        assertFalse(AfkZone.containsAny("anything", " | "));
    }
}
