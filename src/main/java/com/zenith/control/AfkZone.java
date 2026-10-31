package com.zenith.control;

import com.github.rfresh2.EventConsumer;
import com.google.gson.JsonObject;
import com.zenith.Proxy;
import com.zenith.event.client.ClientDisconnectEvent;
import com.zenith.event.client.ClientOnlineEvent;
import com.zenith.module.api.Module;
import org.geysermc.mcprotocollib.network.packet.Packet;
import org.geysermc.mcprotocollib.protocol.packet.ingame.clientbound.ClientboundLoginPacket;
import org.geysermc.mcprotocollib.protocol.packet.ingame.serverbound.ServerboundChatPacket;
import org.jspecify.annotations.Nullable;

import java.util.List;
import java.util.Locale;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;

import static com.github.rfresh2.EventConsumer.of;
import static com.zenith.Globals.*;

/**
 * ZenithHub: knows whether the bot is in the server's AFK zone, what the zone pays, and with "keep" on, takes it
 * there after joining and whenever it ends up outside.
 * <p>
 * The bot counts as in the zone once the server shows {@code confirmText} in a title, subtitle or the action bar.
 * It's out again after a server switch, a move further than {@code leaveDistance} from where it entered, or when a
 * server that kept repeating the confirmation stops. Commands go out after random delays, like a person typing
 * them, and it gives up after {@code maxAttempts} tries instead of repeating a command the server ignores.
 * Nothing is sent while a player controls the account.
 */
public class AfkZone extends Module {
    private static final long CONFIRM_TIMEOUT_MS = 30_000;
    private static final int RETRY_MIN_SECONDS = 45;
    private static final int RETRY_MAX_SECONDS = 120;
    /** A server that repeats the confirmation and then stops for this long has taken the bot out of the zone. */
    private static final long CONFIRM_STALE_MS = 30_000;
    /** In the zone this long without the reward going up: it isn't paying. */
    private static final long NOT_EARNING_AFTER_MS = 4 * 60_000;
    private static final Pattern NUMBER = Pattern.compile("(\\d[\\d.,]*)\\s*(k|m|mio|b|mrd)?\\b", Pattern.CASE_INSENSITIVE);

    public enum State { OFFLINE, OUTSIDE, WAITING, ENTERING, IN_ZONE, PLAYING, FAILED }

    private @Nullable ScheduledFuture<?> tick;

    // in the zone: since when, and where it entered (set on the first tick after the confirmation)
    private volatile long zoneSince;
    private volatile boolean anchored;
    private volatile double anchorX, anchorZ;
    private volatile String anchorDimension;
    private volatile long lastConfirmAt;
    private volatile int confirmCount;

    // getting there
    private volatile long nextAttemptAt;
    private volatile long sentAt;
    private volatile int attempts;
    private volatile @Nullable String failure;

    private volatile long leftAt;
    private volatile @Nullable String leftReason;

    // what the server says while the bot is in the zone, and whether it says the account can't earn
    private volatile @Nullable String serverMessage;
    private volatile long serverMessageAt;
    private volatile long notEarningMessageAt;

    // the reward counter from the sidebar
    private volatile @Nullable Double reward;
    private volatile @Nullable String rewardText;
    private volatile @Nullable Double rewardAtEntry;
    private volatile long rewardRoseAt;

    private volatile State lastReported = State.OFFLINE;
    private volatile @Nullable Double lastReportedReward;

    @Override
    public boolean enabledSetting() {
        // always watching, so the web UI knows about the zone even when someone else took the bot there
        return true;
    }

    @Override
    public List<EventConsumer<?>> registerEvents() {
        return List.of(
            of(ClientOnlineEvent.class, e -> resetConnection()),
            of(ClientDisconnectEvent.class, e -> resetConnection())
        );
    }

    @Override
    public void onEnable() {
        resetConnection();
        tick = EXECUTOR.scheduleAtFixedRate(this::tick, 1, 1, TimeUnit.SECONDS);
    }

    @Override
    public void onDisable() {
        if (tick != null) tick.cancel(false);
    }

    private synchronized void resetConnection() {
        zoneSince = 0;
        anchored = false;
        confirmCount = 0;
        lastConfirmAt = 0;
        nextAttemptAt = 0;
        sentAt = 0;
        attempts = 0;
        failure = null;
        serverMessage = null;
        notEarningMessageAt = 0;
        reward = null;
        rewardText = null;
        rewardAtEntry = null;
        rewardRoseAt = 0;
    }

    /** Server packets, from {@link ServerScreen}. */
    public void onPacket(final Packet packet) {
        // a server switch: proxies like Velocity send a new login, and that's how /afk gets there
        if (packet instanceof ClientboundLoginPacket && isInZone()) left("The server moved the bot to another server");
    }

    /** Titles, subtitles and action bar lines, as plain text. */
    public synchronized void onScreenText(final String text) {
        var c = CONFIG.client.afkZone;
        var lower = text.toLowerCase(Locale.ROOT);
        if (!c.confirmText.isBlank() && lower.contains(c.confirmText.toLowerCase(Locale.ROOT))) {
            lastConfirmAt = System.currentTimeMillis();
            confirmCount++;
            if (!isInZone()) entered();
            return;
        }
        if (!isInZone()) return;
        serverMessage = text;
        serverMessageAt = System.currentTimeMillis();
        if (containsAny(lower, c.notEarningText)) {
            notEarningMessageAt = serverMessageAt;
        }
    }

    /** Whether the lower-case text contains one of the |-separated alternatives. */
    static boolean containsAny(final String lower, final String alternatives) {
        for (var alternative : alternatives.split("\\|")) {
            var a = alternative.strip().toLowerCase(Locale.ROOT);
            if (!a.isEmpty() && lower.contains(a)) return true;
        }
        return false;
    }

    private void entered() {
        zoneSince = System.currentTimeMillis();
        anchored = false;
        confirmCount = 1;
        sentAt = 0;
        nextAttemptAt = 0;
        attempts = 0;
        failure = null;
        serverMessage = null;
        notEarningMessageAt = 0;
        rewardAtEntry = reward;
        rewardRoseAt = 0;
        info("In the AFK zone");
        report();
    }

    private synchronized void left(final String reason) {
        if (!isInZone()) return;
        zoneSince = 0;
        anchored = false;
        confirmCount = 0;
        leftAt = System.currentTimeMillis();
        leftReason = reason;
        serverMessage = null;
        notEarningMessageAt = 0;
        info("Left the AFK zone: {}", reason);
        report();
    }

    /** Kept in the zone, in it, or reading a reward: worth showing on the dashboard. */
    public boolean inUse() {
        return CONFIG.client.afkZone.keep || isInZone() || reward != null;
    }

    public boolean isInZone() {
        return zoneSince > 0;
    }

    private synchronized void tick() {
        try {
            var proxy = Proxy.getInstance();
            var client = proxy.getClient();
            boolean online = proxy.isConnected() && client != null && client.isOnline() && !proxy.isInQueue();
            if (!online) {
                report();
                return;
            }
            var c = CONFIG.client.afkZone;
            long now = System.currentTimeMillis();
            readReward(c.rewardLabel);

            if (isInZone()) {
                var player = CACHE.getPlayerCache().getThePlayer();
                var dimension = CACHE.getChunkCache().getCurrentDimension();
                var dimensionName = dimension != null ? dimension.name() : null;
                if (!anchored && now - zoneSince > 1_000) {
                    anchorX = player.getX();
                    anchorZ = player.getZ();
                    anchorDimension = dimensionName;
                    anchored = true;
                } else if (anchored) {
                    double dx = player.getX() - anchorX, dz = player.getZ() - anchorZ;
                    if (!java.util.Objects.equals(dimensionName, anchorDimension)) {
                        left("It changed dimension");
                    } else if (dx * dx + dz * dz > (double) c.leaveDistance * c.leaveDistance) {
                        left("It moved more than " + c.leaveDistance + " blocks away");
                    }
                }
                if (isInZone() && confirmCount >= 3 && now - lastConfirmAt > CONFIRM_STALE_MS) {
                    left("The server stopped showing the AFK zone");
                }
            }

            boolean playing = proxy.hasActivePlayer();
            if (sentAt > 0 && !isInZone() && now - sentAt > CONFIRM_TIMEOUT_MS) {
                sentAt = 0;
                if (++attempts >= Math.max(1, c.maxAttempts)) {
                    failure = "The server didn't confirm the AFK zone after " + attempts + (attempts == 1 ? " try" : " tries");
                    warn(failure);
                } else {
                    nextAttemptAt = now + random(RETRY_MIN_SECONDS, RETRY_MAX_SECONDS) * 1000L;
                }
            }
            if (c.keep && !isInZone() && !playing && sentAt == 0 && nextAttemptAt == 0 && failure == null) {
                // after a join or a trip out of the zone, give it a moment like a person would
                nextAttemptAt = now + random(c.enterDelayMinSeconds, c.enterDelayMaxSeconds) * 1000L;
            }
            if (nextAttemptAt > 0 && (!c.keep || isInZone() || playing)) {
                nextAttemptAt = 0;
            } else if (nextAttemptAt > 0 && now >= nextAttemptAt) {
                send(c.enterCommand);
            }
            report();
        } catch (final Exception e) {
            warn("AFK zone tick failed", e);
        }
    }

    private void send(final String command) {
        nextAttemptAt = 0;
        sentAt = System.currentTimeMillis();
        info("Going to the AFK zone: /{}", command);
        Proxy.getInstance().getClient().sendAsync(new ServerboundChatPacket("/" + command));
    }

    /** "Go to the AFK zone" from the web UI. */
    public synchronized boolean enterNow() {
        if (!isOnline()) return false;
        failure = null;
        attempts = 0;
        send(CONFIG.client.afkZone.enterCommand);
        report();
        return true;
    }

    /** "Leave the AFK zone" from the web UI. Turns keep off, or the bot would walk straight back. */
    public synchronized boolean leaveNow() {
        if (!isOnline()) return false;
        var c = CONFIG.client.afkZone;
        c.keep = false;
        saveConfigAsync();
        nextAttemptAt = 0;
        sentAt = 0;
        if (!c.leaveCommand.isBlank()) {
            info("Leaving the AFK zone: /{}", c.leaveCommand);
            Proxy.getInstance().getClient().sendAsync(new ServerboundChatPacket("/" + c.leaveCommand));
        }
        left("Left on request");
        report();
        return true;
    }

    /** Keep on: try again right away if it had given up. */
    public synchronized void setKeep(final boolean keep) {
        CONFIG.client.afkZone.keep = keep;
        saveConfigAsync();
        if (keep) {
            failure = null;
            attempts = 0;
        } else {
            nextAttemptAt = 0;
        }
        report();
    }

    private static boolean isOnline() {
        var proxy = Proxy.getInstance();
        var client = proxy.getClient();
        return proxy.isConnected() && client != null && client.isOnline() && !proxy.isInQueue();
    }

    private void readReward(final String label) {
        if (label.isBlank()) {
            reward = null;
            rewardText = null;
            return;
        }
        var sidebar = ServerScreen.sidebar();
        if (sidebar == null) return;
        var lowerLabel = label.toLowerCase(Locale.ROOT);
        for (var line : sidebar.lines()) {
            int i = line.toLowerCase(Locale.ROOT).indexOf(lowerLabel);
            if (i < 0) continue;
            var rest = line.substring(i + label.length()).strip();
            var value = parseAmount(rest);
            if (value == null) continue;
            var previous = reward;
            reward = value;
            rewardText = rest;
            if (previous != null && value > previous) rewardRoseAt = System.currentTimeMillis();
            if (isInZone() && rewardAtEntry == null) rewardAtEntry = value;
            return;
        }
    }

    /** "1,00k", "12.5M", "1.234", "1,234,567" or "7" as a number. */
    static @Nullable Double parseAmount(final String text) {
        var m = NUMBER.matcher(text);
        if (!m.find()) return null;
        var digits = m.group(1);
        var suffix = m.group(2) == null ? "" : m.group(2).toLowerCase(Locale.ROOT);
        double multiplier = switch (suffix) {
            case "k" -> 1e3;
            case "m", "mio" -> 1e6;
            case "b", "mrd" -> 1e9;
            default -> 1;
        };
        int lastDot = digits.lastIndexOf('.'), lastComma = digits.lastIndexOf(',');
        int lastSep = Math.max(lastDot, lastComma);
        String normal;
        if (lastSep < 0) {
            normal = digits;
        } else {
            char sep = digits.charAt(lastSep);
            boolean mixed = lastDot >= 0 && lastComma >= 0;
            long sameSeps = digits.chars().filter(ch -> ch == sep).count();
            int after = digits.length() - lastSep - 1;
            // a lone separator before exactly three digits groups thousands, unless a suffix makes it a fraction
            boolean decimal = mixed || !suffix.isEmpty() || (sameSeps == 1 && after != 3);
            if (decimal) {
                normal = digits.substring(0, lastSep).replaceAll("[.,]", "") + "." + digits.substring(lastSep + 1);
            } else {
                normal = digits.replaceAll("[.,]", "");
            }
        }
        if (normal.endsWith(".")) normal = normal.substring(0, normal.length() - 1);
        try {
            return Double.parseDouble(normal) * multiplier;
        } catch (final NumberFormatException e) {
            return null;
        }
    }

    public State state() {
        if (!isOnline()) return State.OFFLINE;
        if (isInZone()) return State.IN_ZONE;
        if (Proxy.getInstance().hasActivePlayer()) return State.PLAYING;
        if (sentAt > 0) return State.ENTERING;
        if (nextAttemptAt > 0) return State.WAITING;
        if (failure != null) return State.FAILED;
        return State.OUTSIDE;
    }

    /** Earning: unknown until it has been in the zone a while, unless the server says it can't. */
    @Nullable Boolean earning() {
        if (!isInZone()) return null;
        long now = System.currentTimeMillis();
        if (notEarningMessageAt > 0 && now - notEarningMessageAt < 60_000) return false;
        if (rewardRoseAt > zoneSince) return true;
        if (reward != null && now - zoneSince > NOT_EARNING_AFTER_MS) return false;
        return null;
    }

    public synchronized JsonObject json() {
        var c = CONFIG.client.afkZone;
        var json = new JsonObject();
        json.addProperty("state", state().name());
        json.addProperty("keep", c.keep);
        json.addProperty("enterCommand", c.enterCommand);
        json.addProperty("leaveCommand", c.leaveCommand);
        json.addProperty("rewardLabel", c.rewardLabel);
        if (isInZone()) json.addProperty("since", zoneSince);
        if (nextAttemptAt > 0) json.addProperty("nextAttemptAt", nextAttemptAt);
        if (sentAt > 0) json.addProperty("sentAt", sentAt);
        json.addProperty("attempts", attempts);
        json.addProperty("maxAttempts", c.maxAttempts);
        if (failure != null) json.addProperty("failure", failure);
        if (leftAt > 0) {
            json.addProperty("leftAt", leftAt);
            json.addProperty("leftReason", leftReason);
        }
        var earning = earning();
        if (earning != null) json.addProperty("earning", earning);
        if (serverMessage != null && System.currentTimeMillis() - serverMessageAt < 60_000) {
            json.addProperty("serverMessage", serverMessage);
            json.addProperty("serverMessageBlocks", notEarningMessageAt == serverMessageAt);
        }
        if (reward != null) {
            json.addProperty("reward", reward);
            json.addProperty("rewardText", rewardText);
            if (rewardAtEntry != null && isInZone()) json.addProperty("rewardAtEntry", rewardAtEntry);
        }
        return json;
    }

    /** Tells the manager when the state or the reward changes, for the history and notifications. */
    private void report() {
        var state = state();
        var rewardNow = reward;
        if (state == lastReported && java.util.Objects.equals(rewardNow, lastReportedReward)) return;
        lastReported = state;
        lastReportedReward = rewardNow;
        var json = json();
        json.addProperty("type", "afk");
        json.addProperty("ts", System.currentTimeMillis());
        ControlServer.INSTANCE.publish(json);
    }

    private static int random(final int min, final int max) {
        int lo = Math.max(0, Math.min(min, max)), hi = Math.max(min, max);
        return lo == hi ? lo : ThreadLocalRandom.current().nextInt(lo, hi + 1);
    }
}
