package com.zenith.control;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.*;

public class TriggerConditionTest {
    @Test
    public void validatesSpecs() {
        TriggerCondition.validate("player;who=stranger;cooldown=120;ifIdle=1");
        TriggerCondition.validate("player;who=Notch");
        TriggerCondition.validate("daily;at=08:30;days=135;zone=Europe/Berlin;jitter=20");
        TriggerCondition.validate("arrived;x=-120;z=4500;radius=8");
        assertThrows(IllegalArgumentException.class, () -> TriggerCondition.validate("explode"));
        assertThrows(IllegalArgumentException.class, () -> TriggerCondition.validate("player;who=no spaces"));
        assertThrows(IllegalArgumentException.class, () -> TriggerCondition.validate("daily;at=25:99"));
        assertThrows(IllegalArgumentException.class, () -> TriggerCondition.validate("daily;zone=Mars/Base"));
        assertThrows(IllegalArgumentException.class, () -> TriggerCondition.validate("health;below=ten"));
        assertThrows(IllegalArgumentException.class, () -> TriggerCondition.validate("health;unknown=1"));
    }

    @Test
    public void dailyRunsOnTheChosenDaysOnly() {
        var zone = ZoneId.of("Europe/Berlin");
        // Friday 2026-10-30 09:00
        var now = ZonedDateTime.of(2026, 10, 30, 9, 0, 0, 0, zone).toInstant();
        var clock = new AtomicLong(now.toEpochMilli());
        // Mondays and Wednesdays at 08:30
        var trigger = new TriggerCondition("daily;at=08:30;days=13;zone=Europe/Berlin", clock::get);
        assertEquals(ZonedDateTime.of(2026, 11, 2, 8, 30, 0, 0, zone).toInstant(), trigger.getNextDaily());
        assertFalse(trigger.isMet());
        clock.set(trigger.getNextDaily().toEpochMilli());
        assertTrue(trigger.isMet());
        assertFalse(trigger.isMet());
        assertEquals(ZonedDateTime.of(2026, 11, 4, 8, 30, 0, 0, zone).toInstant(), trigger.getNextDaily());
    }

    @Test
    public void dailySkipsRunsItMissed() {
        var zone = ZoneId.of("UTC");
        var start = ZonedDateTime.of(2026, 10, 30, 7, 0, 0, 0, zone).toInstant();
        var clock = new AtomicLong(start.toEpochMilli());
        var trigger = new TriggerCondition("daily;at=08:00;zone=UTC", clock::get);
        // the bot slept through three runs
        clock.set(start.plusSeconds(3 * 86_400).toEpochMilli());
        assertTrue(trigger.isMet());
        assertFalse(trigger.isMet());
        assertEquals(ZonedDateTime.of(2026, 11, 2, 8, 0, 0, 0, zone).toInstant(), trigger.getNextDaily());
    }

    @Test
    public void dailyJitterOnlyMakesItLater() {
        var after = Instant.parse("2026-10-30T07:00:00Z");
        var trigger = new TriggerCondition("daily;at=08:00;zone=UTC;jitter=30", after::toEpochMilli);
        for (int i = 0; i < 50; i++) {
            var next = trigger.nextDaily(after);
            assertFalse(next.isBefore(Instant.parse("2026-10-30T08:00:00Z")));
            assertFalse(next.isAfter(Instant.parse("2026-10-30T08:30:00Z")));
        }
    }

    @Test
    public void dailyHandlesClockChanges() {
        // 02:30 doesn't exist in Berlin on 2026-03-29
        var after = ZonedDateTime.of(2026, 3, 28, 12, 0, 0, 0, ZoneId.of("Europe/Berlin")).toInstant();
        var trigger = new TriggerCondition("daily;at=02:30;zone=Europe/Berlin", after::toEpochMilli);
        assertEquals(Instant.parse("2026-03-29T01:30:00Z"), trigger.nextDaily(after));
    }
}
