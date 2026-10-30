package com.zenith.cache.data.chat;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.BitSet;
import java.util.List;

/**
 * Vanilla's LastSeenMessagesTracker: the signed chat messages we saw. A vanilla client acknowledges them, both in its
 * own signed messages and with a chat ack packet every 64 messages. A server kicks clients that never do.
 */
public class LastSeenMessages {
    public static final int SIZE = 20;
    private static final int ACK_THRESHOLD = 64;
    private final byte[][] tracked = new byte[SIZE][];
    private int tail;
    private int offset;
    private byte[] lastTracked;

    public record Update(List<byte[]> signatures, int offset, BitSet acknowledged) {
        /** As vanilla's LastSeenMessages.computeChecksum: 0 means "not checked", so it's never sent. */
        public byte checksum() {
            int hash = 1;
            for (var signature : signatures) hash = 31 * hash + java.util.Arrays.hashCode(signature);
            byte checksum = (byte) hash;
            return checksum == 0 ? 1 : checksum;
        }
    }

    /** Returns the offset to send in a chat ack packet, or -1 if it's not due yet. */
    public synchronized int addPending(final byte[] signature) {
        if (Arrays.equals(signature, lastTracked)) return -1;
        tracked[tail] = signature;
        tail = (tail + 1) % SIZE;
        offset++;
        lastTracked = signature;
        if (offset > ACK_THRESHOLD) {
            int ack = offset;
            offset = 0;
            return ack;
        }
        return -1;
    }

    public synchronized Update generateAndApplyUpdate() {
        int sentOffset = offset;
        offset = 0;
        var bits = new BitSet(SIZE);
        var signatures = new ArrayList<byte[]>(SIZE);
        for (int j = 0; j < SIZE; j++) {
            var signature = tracked[(tail + j) % SIZE];
            if (signature != null) {
                bits.set(j);
                signatures.add(signature);
            }
        }
        return new Update(signatures, sentOffset, bits);
    }

    public synchronized int offset() {
        return offset;
    }
}
