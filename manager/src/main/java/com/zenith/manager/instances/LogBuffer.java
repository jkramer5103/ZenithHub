package com.zenith.manager.instances;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;

/**
 * Bounded in-memory history of an instance's log lines, for the live console.
 * Zenith also writes full log files into the instance directory.
 */
public final class LogBuffer {
    public record Line(long seq, long ts, String logger, String level, String text, String ansi) {}

    private final int capacity;
    private final ArrayDeque<Line> lines;
    private long nextSeq = 1;

    public LogBuffer(int capacity) {
        this.capacity = capacity;
        this.lines = new ArrayDeque<>(capacity);
    }

    public synchronized Line append(long ts, String logger, String level, String text, String ansi) {
        var line = new Line(nextSeq++, ts, logger, level, text, ansi);
        if (lines.size() == capacity) lines.removeFirst();
        lines.addLast(line);
        return line;
    }

    public synchronized List<Line> tail(int limit) {
        var all = new ArrayList<>(lines);
        return all.subList(Math.max(0, all.size() - limit), all.size());
    }
}
