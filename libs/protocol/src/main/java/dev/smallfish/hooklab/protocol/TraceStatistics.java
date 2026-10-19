package dev.smallfish.hooklab.protocol;

import java.util.Arrays;

/**
 * Statistics for a bounded, recent Hook snapshot.
 *
 * The 95th percentile uses the nearest-rank definition: ceil(0.95 * N) - 1.
 * Negative durations are discarded; the caller must report truncation separately.
 */
public final class TraceStatistics {
    private final int count;
    private final int errorCount;
    private final long p95Nanos;

    private TraceStatistics(int count, int errorCount, long p95Nanos) {
        this.count = count;
        this.errorCount = errorCount;
        this.p95Nanos = p95Nanos;
    }

    public static TraceStatistics from(long[] durationsNs, int errors) {
        if (durationsNs == null) {
            throw new IllegalArgumentException("durations must not be null");
        }
        if (errors < 0 || errors > durationsNs.length) {
            throw new IllegalArgumentException("invalid error count");
        }
        if (durationsNs.length == 0) {
            return new TraceStatistics(0, 0, 0L);
        }
        long[] values = Arrays.copyOf(durationsNs, durationsNs.length);
        for (long ns : values) {
            if (ns < 0) throw new IllegalArgumentException("negative duration");
        }
        Arrays.sort(values);
        int index = Math.max(0, (int) Math.ceil(values.length * 0.95) - 1);
        return new TraceStatistics(values.length, errors, values[index]);
    }

    public int getCount() { return count; }
    public int getErrorCount() { return errorCount; }
    public long getP95Nanos() { return p95Nanos; }

    public double getErrorRate() {
        return count == 0 ? 0.0 : (double) errorCount / count;
    }
}
