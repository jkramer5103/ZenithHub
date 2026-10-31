package com.zenith.control;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

public class ServerBanTest {
    private static void field(ByteArrayOutputStream out, String name, String value) {
        var n = name.getBytes(StandardCharsets.US_ASCII);
        var v = value.getBytes(StandardCharsets.UTF_8);
        out.write(n.length);
        out.writeBytes(n);
        out.write(0);
        out.write(v.length);
        out.writeBytes(v);
    }

    /** Laid out like HugoSMP's hugosmp:vivi cookie: an id, timestamps and the host first, then the fields. */
    private static byte[] hugoSmpCookie() {
        var out = new ByteArrayOutputStream();
        out.writeBytes(new byte[] { 2, 100, -36, -17, -79, -7, 44, 67, -92, -73, 0, 0, 1, -95, -110, 108, 41, -48, 99, -35 });
        out.write(16);
        out.writeBytes("java.hugosmp.net".getBytes(StandardCharsets.US_ASCII));
        out.write(5);
        out.writeBytes("de_DE".getBytes(StandardCharsets.US_ASCII));
        out.writeBytes(new byte[] { 1, 7 });
        field(out, "case", "");
        field(out, "duration", "30 Tage");
        field(out, "reason", "Extremverhalten Botting AFK-Welle Hosting 31.10.");
        field(out, "since", "31.10.26, 12:58");
        field(out, "since_ms", "1793447931030");
        field(out, "until", "30.11.26, 12:58");
        field(out, "until_ms", "1796039931030");
        out.writeBytes(new byte[] { -19, -91, -18, -106, 108, -128, 90 });
        return out.toByteArray();
    }

    @Test
    public void readsHugoSmpsBanCookie() {
        var ban = ServerBan.parse("hugosmp:vivi", hugoSmpCookie(), 5);
        assertNotNull(ban);
        assertEquals("Extremverhalten Botting AFK-Welle Hosting 31.10.", ban.reason());
        assertEquals("30 Tage", ban.duration());
        assertEquals("30.11.26, 12:58", ban.until());
        assertEquals(1796039931030L, ban.untilMs());
        assertEquals("hugosmp:vivi", ban.key());
    }

    @Test
    public void otherCookiesAreNoBan() {
        assertNull(ServerBan.parse("zenith:target", "lobby".getBytes(StandardCharsets.UTF_8), 5));
        assertNull(ServerBan.parse("x:y", new byte[0], 5));
        var out = new ByteArrayOutputStream();
        field(out, "reason", "no end given");
        assertNull(ServerBan.parse("x:y", out.toByteArray(), 5));
    }
}
