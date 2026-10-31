package com.zenith.control;

import com.github.rfresh2.EventConsumer;
import com.zenith.Proxy;
import com.zenith.event.module.ServerPlayerAttackedUsEvent;
import com.zenith.event.module.TotemPopEvent;
import com.zenith.event.module.VisualRangeEnterEvent;
import com.zenith.feature.tasks.Condition;
import lombok.Getter;
import org.jspecify.annotations.Nullable;

import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.LongSupplier;
import java.util.regex.Pattern;

import static com.zenith.Globals.CACHE;
import static com.zenith.Globals.EVENT_BUS;
import static com.zenith.Globals.MODULE;

/**
 * ZenithHub: a task trigger with settings, for the web UI's "When…" tasks. All of it lives in one spec string
 * like {@code player;who=stranger;cooldown=120}, which is also how it is saved.
 *
 * <p>Triggers fire when something starts (the bot arrives, health drops below a level), never again while it
 * stays that way. On top of that a task runs at most once per cooldown, can wait a random delay first so it
 * doesn't look scripted, and can skip running while someone is playing on the account.
 */
public final class TriggerCondition implements Condition {
    public static final Set<String> KINDS = Set.of("afk", "player", "health", "attacked", "totem", "daily", "unattended", "arrived", "onlineFor");
    public static final int MIN_COOLDOWN_SECONDS = 10;
    public static final int DEFAULT_COOLDOWN_SECONDS = 60;
    private static final Pattern NAME = Pattern.compile("[A-Za-z0-9_]{1,16}");
    private static final Pattern NUMBER = Pattern.compile("-?\\d{1,9}");

    @Getter private final String spec;
    @Getter private final String kind;
    private final Map<String, String> params;
    private final long cooldownMs;
    private final long maxDelayMs;
    private final boolean ifIdle;
    private final LongSupplier clock;

    // set from event threads, read on the tasks tick
    private final AtomicBoolean fired = new AtomicBoolean();
    private long lastFiredAt = Long.MIN_VALUE / 2;
    private long runAt = -1;

    // what was true last tick, to fire on changes only
    private @Nullable Object last;
    private boolean armed = true;
    private long since = -1;
    @Getter private @Nullable Instant nextDaily;

    public TriggerCondition(String spec) {
        this(spec, System::currentTimeMillis);
    }

    TriggerCondition(String spec, LongSupplier clock) {
        this.spec = spec;
        this.clock = clock;
        this.params = parse(spec);
        this.kind = params.remove("");
        int cooldown = Math.max(MIN_COOLDOWN_SECONDS, integer("cooldown", DEFAULT_COOLDOWN_SECONDS));
        this.cooldownMs = cooldown * 1000L;
        this.maxDelayMs = Math.clamp(integer("delay", 0), 0, 3600) * 1000L;
        this.ifIdle = "1".equals(params.get("ifIdle"));
        switch (kind) {
            case "player" -> EVENT_BUS.subscribe(this, EventConsumer.of(VisualRangeEnterEvent.class, this::onEnter));
            case "attacked" -> EVENT_BUS.subscribe(this, EventConsumer.of(ServerPlayerAttackedUsEvent.class, e -> fired.set(true)));
            case "totem" -> EVENT_BUS.subscribe(this, EventConsumer.of(TotemPopEvent.class, e -> {
                if (e.entityId() == CACHE.getPlayerCache().getEntityId()) fired.set(true);
            }));
            case "daily" -> nextDaily = nextDaily(Instant.ofEpochMilli(clock.getAsLong()));
            default -> {}
        }
    }

    /**
     * Checks a spec, or throws with a message for the user.
     * The manager builds specs; this is the bot's own check before it saves one.
     */
    public static String validate(String spec) {
        var params = parse(spec);
        var kind = params.get("");
        if (kind == null || !KINDS.contains(kind)) throw new IllegalArgumentException("Unknown trigger: " + kind);
        for (var entry : params.entrySet()) {
            var key = entry.getKey();
            var value = entry.getValue();
            boolean ok = switch (key) {
                case "" -> true;
                case "cooldown", "delay", "minutes", "below", "radius", "jitter", "x", "z" -> NUMBER.matcher(value).matches();
                case "ifIdle" -> value.equals("1") || value.equals("0");
                case "on" -> Set.of("earningStopped", "earningStarted", "leftZone").contains(value);
                case "who" -> value.equals("anyone") || value.equals("stranger") || NAME.matcher(value).matches();
                case "at" -> value.matches("\\d{2}:\\d{2}");
                case "days" -> value.matches("[1-7]{1,7}");
                case "zone" -> ZoneId.getAvailableZoneIds().contains(value);
                default -> false;
            };
            if (!ok) throw new IllegalArgumentException("Bad trigger setting " + key + "=" + value);
        }
        if (kind.equals("daily")) {
            try {
                LocalTime.parse(params.getOrDefault("at", ""));
            } catch (RuntimeException e) {
                throw new IllegalArgumentException("Bad time " + params.get("at"));
            }
        }
        return spec;
    }

    private static Map<String, String> parse(String spec) {
        var params = new LinkedHashMap<String, String>();
        var parts = spec.split(";");
        params.put("", parts[0]);
        for (int i = 1; i < parts.length; i++) {
            int eq = parts[i].indexOf('=');
            if (eq <= 0) throw new IllegalArgumentException("Bad trigger setting " + parts[i]);
            params.put(parts[i].substring(0, eq), parts[i].substring(eq + 1));
        }
        return params;
    }

    private int integer(String key, int fallback) {
        var value = params.get(key);
        if (value == null) return fallback;
        try {
            return Integer.parseInt(value);
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    @Override
    public boolean isMet() {
        long now = clock.getAsLong();
        boolean happened = fired.getAndSet(false) | detect(now);
        if (happened && runAt < 0 && now - lastFiredAt >= cooldownMs) {
            lastFiredAt = now;
            runAt = now + (maxDelayMs > 0 ? ThreadLocalRandom.current().nextLong(maxDelayMs + 1) : 0);
        }
        if (runAt < 0 || now < runAt) return false;
        runAt = -1;
        // someone took over in the meantime: leave them alone
        return !(ifIdle && Proxy.getInstance().hasActivePlayer());
    }

    /** The state-based triggers, polled every tick. */
    private boolean detect(long now) {
        return switch (kind) {
            case "afk" -> afk();
            case "health" -> health();
            case "daily" -> daily(now);
            case "unattended" -> unattended(now);
            case "arrived" -> arrived();
            case "onlineFor" -> onlineFor(now);
            default -> false;
        };
    }

    private void onEnter(VisualRangeEnterEvent event) {
        var who = params.getOrDefault("who", "anyone");
        boolean match = switch (who) {
            case "anyone" -> true;
            case "stranger" -> !event.isFriend();
            default -> event.playerEntry().getName() != null && event.playerEntry().getName().equalsIgnoreCase(who);
        };
        if (match) fired.set(true);
    }

    private boolean afk() {
        var zone = MODULE.get(AfkZone.class);
        var state = zone.state();
        var earning = zone.earning();
        var now = new Object[] { state, earning };
        var before = (Object[]) last;
        last = now;
        if (before == null) return false;
        return switch (params.getOrDefault("on", "earningStopped")) {
            case "earningStarted" -> before[1] != Boolean.TRUE && earning == Boolean.TRUE;
            case "leftZone" -> before[0] == AfkZone.State.IN_ZONE && state != AfkZone.State.IN_ZONE && state != AfkZone.State.OFFLINE;
            default -> before[1] == Boolean.TRUE && earning != Boolean.TRUE;
        };
    }

    private boolean health() {
        if (!inGame()) {
            armed = true;
            return false;
        }
        float health = CACHE.getPlayerCache().getThePlayer().getHealth();
        int below = Math.clamp(integer("below", 10), 1, 19);
        // 0 means not known yet, right after joining; dying has its own trigger
        if (health <= 0) return false;
        if (health >= below + 2) armed = true;
        if (armed && health < below) {
            armed = false;
            return true;
        }
        return false;
    }

    private boolean daily(long now) {
        if (nextDaily == null || now < nextDaily.toEpochMilli()) return false;
        // runs it missed while the bot was off are skipped, not made up
        nextDaily = nextDaily(Instant.ofEpochMilli(now));
        return true;
    }

    /** The next time on one of the days, after {@code after}, plus a random offset of up to the jitter. */
    @Nullable Instant nextDaily(Instant after) {
        LocalTime at;
        try {
            at = LocalTime.parse(params.getOrDefault("at", "08:00"));
        } catch (RuntimeException e) {
            return null;
        }
        ZoneId zone;
        try {
            zone = params.containsKey("zone") ? ZoneId.of(params.get("zone")) : ZoneId.systemDefault();
        } catch (RuntimeException e) {
            zone = ZoneId.systemDefault();
        }
        var days = EnumSet.noneOf(DayOfWeek.class);
        for (char c : params.getOrDefault("days", "1234567").toCharArray()) {
            if (c >= '1' && c <= '7') days.add(DayOfWeek.of(c - '0'));
        }
        if (days.isEmpty()) days = EnumSet.allOf(DayOfWeek.class);
        long jitterMs = Math.clamp(integer("jitter", 0), 0, 720) * 60_000L;
        var date = after.atZone(zone).toLocalDate();
        for (int i = 0; i < 8; i++, date = date.plusDays(1)) {
            if (!days.contains(date.getDayOfWeek())) continue;
            // a time skipped by a clock change moves to just after it
            var nominal = ZonedDateTime.of(date, at, zone).toInstant();
            if (nominal.isAfter(after)) {
                return nominal.plusMillis(jitterMs > 0 ? ThreadLocalRandom.current().nextLong(jitterMs + 1) : 0);
            }
        }
        return null;
    }

    private boolean unattended(long now) {
        // counts from when the bot came online or was last played on, whichever was later
        if (!inGame() || Proxy.getInstance().hasActivePlayer()) {
            since = now;
            armed = true;
            return false;
        }
        if (since < 0) since = now;
        if (armed && now - since >= Math.max(1, integer("minutes", 10)) * 60_000L) {
            armed = false;
            return true;
        }
        return false;
    }

    private boolean arrived() {
        if (!inGame()) {
            last = null;
            return false;
        }
        var cache = CACHE.getPlayerCache();
        double dx = cache.getX() - integer("x", 0);
        double dz = cache.getZ() - integer("z", 0);
        int radius = Math.clamp(integer("radius", 5), 1, 10_000);
        boolean inside = dx * dx + dz * dz <= (double) radius * radius;
        var before = last;
        last = inside;
        // already there when the task was added or the bot joined: that's not arriving
        return before == Boolean.FALSE && inside;
    }

    private boolean onlineFor(long now) {
        if (!inGame()) {
            since = -1;
            armed = true;
            return false;
        }
        if (since < 0) {
            // counts from the connect, also when the task is added later
            var connected = Proxy.getInstance().getConnectTime();
            since = connected != null ? Math.min(now, connected.toEpochMilli()) : now;
        }
        if (armed && now - since >= Math.max(1, integer("minutes", 60)) * 60_000L) {
            armed = false;
            return true;
        }
        return false;
    }

    private static boolean inGame() {
        var proxy = Proxy.getInstance();
        var client = proxy.getClient();
        return proxy.isConnected() && client != null && client.isOnline() && !proxy.isInQueue();
    }

    @Override
    public void close() {
        EVENT_BUS.unsubscribe(this);
    }
}
