package dev.smallfish.hooklab.demo;

/**
 * Deterministic methods owned by this project, intended for safe Hook testing.
 * These methods never access accounts, the network or private device information.
 */
public class ImageProcessor {
    public float adjustBrightness(float normalized, int deltaPercent) {
        return Math.max(0.0f, Math.min(1.0f, normalized + deltaPercent / 100.0f));
    }

    public int adjustBrightness(int base, int delta) {
        return Math.max(0, Math.min(255, base + delta));
    }

    public int validateLevel(int level) {
        if (level < 0 || level > 100) {
            throw new IllegalArgumentException("Level must be in [0, 100]");
        }
        return level;
    }
}
