package com.zenith.control;

import net.kyori.adventure.key.Key;
import org.jspecify.annotations.Nullable;

import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.Consumer;

import static com.zenith.Globals.CLIENT_LOG;

/**
 * ZenithHub: a ban the server wrote into a cookie. HugoSMP stores one ({@code hugosmp:vivi}: reason, duration, until)
 * right before it moves a banned player to a holding server that only says "maintenance", so without this the bot
 * looks online forever. The manager disconnects the instance and shows the ban.
 */
public final class ServerBan {
    public record Ban(String key, String reason, @Nullable String duration, @Nullable String until, long untilMs, long seenAt) {}

    private static volatile @Nullable Ban current;
    private static volatile Consumer<Ban> listener = ban -> {};

    private ServerBan() {}

    public static @Nullable Ban current() {
        return current;
    }

    static void onBan(Consumer<Ban> l) {
        listener = l;
    }

    /** Called for every cookie the server stores, whether or not cookies are kept. */
    public static void onCookie(final Key key, final byte[] payload) {
        var ban = parse(key.asString(), payload, System.currentTimeMillis());
        if (ban == null) return;
        current = ban;
        CLIENT_LOG.warn("The server banned this account: {} (until {})", ban.reason(), ban.until() != null ? ban.until() : ban.duration());
        listener.accept(ban);
    }

    /** A ban record needs a reason and an end (a date or a duration); anything else is some other cookie. */
    static @Nullable Ban parse(String key, byte[] payload, long now) {
        var fields = fields(payload);
        var reason = fields.get("reason");
        var until = fields.get("until");
        var duration = fields.get("duration");
        if (reason == null || reason.isBlank() || (until == null && duration == null)) return null;
        long untilMs = 0;
        try {
            if (fields.containsKey("until_ms")) untilMs = Long.parseLong(fields.get("until_ms").trim());
        } catch (NumberFormatException ignored) {
            // the readable date is still there
        }
        return new Ban(key, reason.trim(), duration, until, untilMs, now);
    }

    /**
     * Name/value pairs in the binary formats servers use for this: a length-prefixed lowercase name, a 0 type byte,
     * then a varint-length UTF-8 value. Unknown bytes in between are skipped.
     */
    static Map<String, String> fields(byte[] b) {
        var out = new LinkedHashMap<String, String>();
        for (int i = 0; i < b.length; i++) {
            int nameLen = b[i] & 0xff;
            if (nameLen < 2 || nameLen > 32 || i + 1 + nameLen + 2 > b.length) continue;
            if (!isName(b, i + 1, nameLen)) continue;
            int p = i + 1 + nameLen;
            if (b[p] != 0) continue;
            p++;
            int len = 0, shift = 0;
            while (p < b.length && shift < 21) {
                int v = b[p++] & 0xff;
                len |= (v & 0x7f) << shift;
                shift += 7;
                if ((v & 0x80) == 0) break;
            }
            if (len > 1024 || p + len > b.length) continue;
            try {
                var value = StandardCharsets.UTF_8.newDecoder().decode(ByteBuffer.wrap(b, p, len)).toString();
                out.putIfAbsent(new String(b, i + 1, nameLen, StandardCharsets.US_ASCII), value);
                i = p + len - 1;
            } catch (CharacterCodingException ignored) {
                // not text
            }
        }
        return out;
    }

    private static boolean isName(byte[] b, int from, int len) {
        for (int i = from; i < from + len; i++) {
            int c = b[i];
            if (!(c >= 'a' && c <= 'z') && c != '_') return false;
        }
        return true;
    }
}
