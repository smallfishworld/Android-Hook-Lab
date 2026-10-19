package dev.smallfish.hooklab.protocol;

import org.junit.Test;
import static org.junit.Assert.assertEquals;

public class TraceStatisticsTest {
    @Test public void returnsZerosForEmpty() {
        TraceStatistics stats = TraceStatistics.from(new long[0], 0);
        assertEquals(0, stats.getCount());
        assertEquals(0L, stats.getP95Nanos());
        assertEquals(0.0, stats.getErrorRate(), 0.0001);
    }

    @Test public void usesNearestRankP95() {
        long[] durations = new long[100];
        for (int i = 0; i < 100; i++) durations[i] = 100 - i;
        TraceStatistics stats = TraceStatistics.from(durations, 4);
        assertEquals(100, stats.getCount());
        assertEquals(4, stats.getErrorCount());
        assertEquals(95L, stats.getP95Nanos());
        assertEquals(0.04, stats.getErrorRate(), 0.00001);
    }

    @Test(expected = IllegalArgumentException.class)
    public void refusesInvalidErrorCount() {
        TraceStatistics.from(new long[]{1, 2}, 3);
    }

    @Test(expected = IllegalArgumentException.class)
    public void refusesNegativeDuration() {
        TraceStatistics.from(new long[]{1, -3}, 0);
    }

    @Test(expected = IllegalArgumentException.class)
    public void refusesNullInput() {
        TraceStatistics.from(null, 0);
    }
}
