package dev.joycon2.bridge.output;

import java.util.Locale;

/** Observes the physical right-stick X axis and detects a conservative one-sided stream. */
final class RightStickAxisMonitor {
    private static final float NEGATIVE_EXCURSION = -0.45f;
    private static final float POSITIVE_EXCURSION = 0.20f;
    private static final float CENTER_BAND = 0.10f;
    private static final int REQUIRED_LEFT_CENTER_CYCLES = 2;
    private static final int REQUIRED_SAMPLES = 12;
    private static final long REQUIRED_OBSERVATION_MS = 4_000L;

    private boolean configured;
    private boolean inNegativeExcursion;
    private boolean positiveSeen;
    private boolean recoveryRequested;
    private int current;
    private int minimum;
    private int maximum;
    private int minSeen;
    private int maxSeen;
    private int sampleCount;
    private int leftCenterCycles;
    private long firstNegativeAt = -1L;

    void configure(int value, int minimum, int maximum, long nowMs) {
        configured = maximum > minimum;
        current = value;
        this.minimum = minimum;
        this.maximum = maximum;
        minSeen = value;
        maxSeen = value;
        sampleCount = 0;
        leftCenterCycles = 0;
        firstNegativeAt = -1L;
        inNegativeExcursion = false;
        positiveSeen = false;
        recoveryRequested = false;
        if (configured) {
            record(value, nowMs);
        }
    }

    boolean record(int value, long nowMs) {
        if (!configured || recoveryRequested) {
            return false;
        }
        current = value;
        minSeen = Math.min(minSeen, value);
        maxSeen = Math.max(maxSeen, value);
        sampleCount++;

        float normalized = normalize(value);
        if (normalized >= POSITIVE_EXCURSION) {
            positiveSeen = true;
        }
        if (normalized <= NEGATIVE_EXCURSION) {
            if (firstNegativeAt < 0L) {
                firstNegativeAt = nowMs;
            }
            inNegativeExcursion = true;
        } else if (inNegativeExcursion && Math.abs(normalized) <= CENTER_BAND) {
            leftCenterCycles++;
            inNegativeExcursion = false;
        }
        return shouldRecover(nowMs);
    }

    boolean shouldRecover(long nowMs) {
        return configured
                && !positiveSeen
                && !recoveryRequested
                && firstNegativeAt >= 0L
                && leftCenterCycles >= REQUIRED_LEFT_CENTER_CYCLES
                && sampleCount >= REQUIRED_SAMPLES
                && nowMs - firstNegativeAt >= REQUIRED_OBSERVATION_MS;
    }

    void markRecoveryRequested() {
        recoveryRequested = true;
    }

    boolean hasPositiveExcursion() {
        return positiveSeen;
    }

    String summary() {
        String state;
        if (!configured) {
            state = "invalid-range";
        } else if (recoveryRequested) {
            state = "recovery-requested";
        } else if (positiveSeen) {
            state = "healthy";
        } else if (leftCenterCycles >= REQUIRED_LEFT_CENTER_CYCLES) {
            state = "one-sided-suspect";
        } else {
            state = "observing";
        }
        return String.format(
                Locale.ROOT,
                "Raw axis R.ABS_RX current=%d seen=[%d,%d] range=[%d,%d] samples=%d "
                        + "left-center-cycles=%d state=%s",
                current,
                minSeen,
                maxSeen,
                minimum,
                maximum,
                sampleCount,
                leftCenterCycles,
                state
        );
    }

    private float normalize(int value) {
        float center = (minimum + maximum) / 2f;
        float delta = value - center;
        float range = delta < 0f ? center - minimum : maximum - center;
        if (range <= 0f) {
            return 0f;
        }
        return Math.max(-1f, Math.min(1f, delta / range));
    }
}
