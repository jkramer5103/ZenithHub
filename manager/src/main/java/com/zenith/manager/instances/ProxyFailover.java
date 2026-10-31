package com.zenith.manager.instances;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashMap;
import java.util.Map;

/** Counts an account's dropped connections and says when its proxy should be swapped. */
public final class ProxyFailover {
    static final int DROPS = 2;
    static final long WINDOW_MS = 2 * 60 * 60_000L;

    private final Map<String, Deque<Long>> drops = new HashMap<>();

    /** A network failure on the account's connection. True when that makes {@value #DROPS} within the window. */
    public synchronized boolean dropped(String accountId, long ts) {
        var times = drops.computeIfAbsent(accountId, id -> new ArrayDeque<>());
        while (!times.isEmpty() && ts - times.peekFirst() > WINDOW_MS) times.removeFirst();
        times.addLast(ts);
        if (times.size() < DROPS) return false;
        // the new proxy starts with a clean slate
        times.clear();
        return true;
    }
}
