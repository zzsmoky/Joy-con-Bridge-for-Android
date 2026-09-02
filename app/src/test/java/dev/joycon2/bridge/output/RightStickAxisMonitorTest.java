package dev.joycon2.bridge.output;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public final class RightStickAxisMonitorTest {
    @Test
    public void healthyPositiveMotionPreventsRecovery() {
        RightStickAxisMonitor monitor = configuredMonitor();
        feedLeftCenterCycle(monitor, 100L);
        monitor.record(12_000, 1_000L);
        feedLeftCenterCycle(monitor, 1_500L);

        assertTrue(monitor.hasPositiveExcursion());
        assertFalse(monitor.shouldRecover(10_000L));
        assertTrue(monitor.summary().contains("state=healthy"));
    }

    @Test
    public void repeatedNegativeOnlyMotionRequestsRecoveryAfterObservation() {
        RightStickAxisMonitor monitor = configuredMonitor();
        feedLeftCenterCycle(monitor, 100L);
        feedLeftCenterCycle(monitor, 1_000L);
        for (int index = 0; index < 8; index++) {
            monitor.record(-4_000 - index, 1_800L + index * 100L);
        }

        assertFalse(monitor.shouldRecover(3_999L));
        assertTrue(monitor.shouldRecover(4_100L));
        assertTrue(monitor.summary().contains("state=one-sided-suspect"));
    }

    @Test
    public void holdingLeftWithoutReturningToCenterDoesNotRecover() {
        RightStickAxisMonitor monitor = configuredMonitor();
        for (int index = 0; index < 20; index++) {
            monitor.record(-20_000 - index, 100L + index * 300L);
        }

        assertFalse(monitor.shouldRecover(10_000L));
    }

    @Test
    public void invalidAxisRangeDisablesRecovery() {
        RightStickAxisMonitor monitor = new RightStickAxisMonitor();
        monitor.configure(0, 0, 0, 0L);
        monitor.record(-20_000, 10_000L);

        assertFalse(monitor.shouldRecover(20_000L));
        assertTrue(monitor.summary().contains("state=invalid-range"));
    }

    private static RightStickAxisMonitor configuredMonitor() {
        RightStickAxisMonitor monitor = new RightStickAxisMonitor();
        monitor.configure(0, -32_767, 32_767, 0L);
        return monitor;
    }

    private static void feedLeftCenterCycle(RightStickAxisMonitor monitor, long startMs) {
        monitor.record(-18_000, startMs);
        monitor.record(-25_000, startMs + 100L);
        monitor.record(-8_000, startMs + 200L);
        monitor.record(0, startMs + 300L);
    }
}
