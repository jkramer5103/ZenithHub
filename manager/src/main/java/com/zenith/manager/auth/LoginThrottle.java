package com.zenith.manager.auth;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Slows down password guessing: after 5 failures from one address, lock it out for a growing duration.
 */
public final class LoginThrottle {
    private static final int FREE_ATTEMPTS = 5;

    private static final class Entry {
        int failures;
        long lockedUntil;
    }

    private final Map<String, Entry> entries = new ConcurrentHashMap<>();

    /** Milliseconds until another attempt is allowed, 0 if allowed now. */
    public long retryAfterMs(String key) {
        var e = entries.get(key);
        if (e == null) return 0;
        return Math.max(0, e.lockedUntil - System.currentTimeMillis());
    }

    public void failure(String key) {
        var e = entries.computeIfAbsent(key, k -> new Entry());
        synchronized (e) {
            e.failures++;
            if (e.failures >= FREE_ATTEMPTS) {
                long lockMs = Math.min(15 * 60_000L, 30_000L << Math.min(5, e.failures - FREE_ATTEMPTS));
                e.lockedUntil = System.currentTimeMillis() + lockMs;
            }
        }
    }

    public void success(String key) {
        entries.remove(key);
    }
}
