package com.zenith.control;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.zenith.util.ComponentSerializer;
import net.kyori.adventure.text.Component;
import org.geysermc.mcprotocollib.network.packet.Packet;
import org.geysermc.mcprotocollib.protocol.data.game.scoreboard.ScoreboardPosition;
import org.geysermc.mcprotocollib.protocol.packet.ingame.clientbound.ClientboundSystemChatPacket;
import org.geysermc.mcprotocollib.protocol.packet.ingame.clientbound.title.ClientboundClearTitlesPacket;
import org.geysermc.mcprotocollib.protocol.packet.ingame.clientbound.title.ClientboundSetActionBarTextPacket;
import org.geysermc.mcprotocollib.protocol.packet.ingame.clientbound.title.ClientboundSetSubtitleTextPacket;
import org.geysermc.mcprotocollib.protocol.packet.ingame.clientbound.title.ClientboundSetTitleTextPacket;

import java.util.Comparator;

import static com.zenith.Globals.CACHE;
import static com.zenith.Globals.MODULE;

/**
 * ZenithHub: what the server shows on screen besides chat (sidebar, boss bars, action bar, titles, tab header and
 * footer), as plain text. Servers like HugoSMP report AFK-zone rewards and timers there, so the web UI and the
 * AFK zone module read it from here.
 */
public final class ServerScreen {
    /** How long an action bar line stays relevant; vanilla shows one for about 3 s, servers resend them. */
    private static final long ACTION_BAR_MS = 5_000;
    /** Vanilla shows a title for 5 s by default; servers that keep one up resend it. */
    private static final long TITLE_MS = 10_000;

    private static volatile Text actionBar;
    private static volatile Text title;
    private static volatile Text subtitle;

    public record Text(String text, long at) {}

    private ServerScreen() {}

    /** Called for every packet from the server. */
    public static void onPacket(final Packet packet) {
        switch (packet) {
            case ClientboundSetActionBarTextPacket p -> actionBar = shown(p.getText());
            case ClientboundSystemChatPacket p when p.isOverlay() -> actionBar = shown(p.getContent());
            case ClientboundSetTitleTextPacket p -> title = shown(p.getText());
            case ClientboundSetSubtitleTextPacket p -> subtitle = shown(p.getText());
            case ClientboundClearTitlesPacket p -> {
                title = null;
                subtitle = null;
            }
            default -> { }
        }
        var afkZone = MODULE.get(AfkZone.class);
        if (afkZone != null) afkZone.onPacket(packet);
    }

    private static Text shown(final Component component) {
        if (component == null) return null;
        var plain = clean(ComponentSerializer.serializePlain(component));
        if (plain.isEmpty()) return null;
        var afkZone = MODULE.get(AfkZone.class);
        if (afkZone != null) afkZone.onScreenText(plain);
        return new Text(plain, System.currentTimeMillis());
    }

    public static void reset() {
        actionBar = null;
        title = null;
        subtitle = null;
    }

    /** The last action bar line, if it's still on screen. */
    public static String actionBar() {
        var text = actionBar;
        return recent(text, ACTION_BAR_MS) ? text.text() : null;
    }

    private static boolean recent(final Text text, final long ms) {
        return text != null && System.currentTimeMillis() - text.at() < ms;
    }

    /** Sidebar title and lines, top to bottom, as the vanilla client renders them. */
    public static Sidebar sidebar() {
        var scoreboard = CACHE.getScoreboardCache();
        var name = scoreboard.getCachedPositionObjectives().get(ScoreboardPosition.SIDEBAR);
        if (name == null) return null;
        var objective = scoreboard.get(name);
        if (objective == null) return null;
        var lines = objective.getScores().values().stream()
            .sorted(Comparator.comparingInt((com.zenith.cache.data.scoreboard.Score s) -> -s.getValue())
                        .thenComparing(com.zenith.cache.data.scoreboard.Score::getOwner))
            .limit(15)
            .map(score -> {
                if (score.getDisplay() != null) return plain(score.getDisplay());
                // plugins usually put the text in the owner's team prefix and suffix
                var team = CACHE.getTeamCache().getTeamsByPlayer().get(score.getOwner());
                if (team == null) return score.getOwner();
                return plain(team.getPrefix()) + score.getOwner() + plain(team.getSuffix());
            })
            .map(ServerScreen::clean)
            .toList();
        return new Sidebar(clean(plain(objective.getDisplayName())), lines);
    }

    public record Sidebar(String title, java.util.List<String> lines) {}

    private static String plain(final Component component) {
        return component == null ? "" : ComponentSerializer.serializePlain(component);
    }

    /**
     * Without legacy § codes, and without the icons servers draw with resource pack fonts: those use private use
     * characters, or Yi syllables (HugoSMP), which mean nothing as text.
     */
    static String clean(final String text) {
        var out = new StringBuilder(text.length());
        var stripped = text.replaceAll("§.", "");
        stripped.codePoints().forEach(cp -> {
            var block = Character.UnicodeBlock.of(cp);
            if (block == Character.UnicodeBlock.PRIVATE_USE_AREA
                || block == Character.UnicodeBlock.SUPPLEMENTARY_PRIVATE_USE_AREA_A
                || block == Character.UnicodeBlock.SUPPLEMENTARY_PRIVATE_USE_AREA_B
                || block == Character.UnicodeBlock.YI_SYLLABLES
                || block == Character.UnicodeBlock.YI_RADICALS) return;
            out.appendCodePoint(cp);
        });
        return out.toString().replaceAll("\\s+", " ").strip();
    }

    public static JsonObject json() {
        var json = new JsonObject();
        var sidebar = sidebar();
        if (sidebar != null) {
            var s = new JsonObject();
            s.addProperty("title", sidebar.title());
            var lines = new JsonArray();
            sidebar.lines().forEach(lines::add);
            s.add("lines", lines);
            json.add("sidebar", s);
        }
        var bossBars = new JsonArray();
        CACHE.getBossBarCache().getBossBars().values().forEach(bar -> {
            var b = new JsonObject();
            b.addProperty("title", clean(plain(bar.getTitle())));
            b.addProperty("progress", bar.getHealth());
            b.addProperty("color", bar.getColor() != null ? bar.getColor().name().toLowerCase() : null);
            bossBars.add(b);
        });
        json.add("bossBars", bossBars);
        json.addProperty("actionBar", actionBar());
        var t = title;
        if (recent(t, TITLE_MS)) json.addProperty("title", t.text());
        var st = subtitle;
        if (recent(st, TITLE_MS)) json.addProperty("subtitle", st.text());
        var tab = CACHE.getTabListCache();
        var header = clean(plain(tab.getHeader()));
        var footer = clean(plain(tab.getFooter()));
        if (!header.isEmpty()) json.addProperty("tabHeader", header);
        if (!footer.isEmpty()) json.addProperty("tabFooter", footer);
        return json;
    }
}
