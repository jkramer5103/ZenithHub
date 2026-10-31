package com.zenith.manager;

import com.zenith.manager.instances.ProxyFailover;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ProxyFailoverTest {
    @Test
    void theSecondDropWithinTwoHoursSwapsTheProxy() {
        var failover = new ProxyFailover();
        long t = 1_000_000_000L;
        long hour = 3_600_000L;
        assertFalse(failover.dropped("a", t));
        assertFalse(failover.dropped("b", t + hour));
        assertTrue(failover.dropped("a", t + hour));
        // counting starts over with the new proxy
        assertFalse(failover.dropped("a", t + hour + 1));
        // too far apart
        assertTrue(failover.dropped("a", t + 2 * hour));
        assertFalse(failover.dropped("c", t));
        assertFalse(failover.dropped("c", t + 3 * hour));
    }
}
