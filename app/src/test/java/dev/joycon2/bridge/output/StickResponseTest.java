package dev.joycon2.bridge.output;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public final class StickResponseTest {
    @Test
    public void removesObservedCenterDrift() {
        assertEquals(0f, StickResponse.apply(0.13f), 0f);
        assertEquals(0f, StickResponse.apply(-StickResponse.DEAD_ZONE), 0f);
    }

    @Test
    public void softensPartialMovementButPreservesFullRange() {
        float positive = StickResponse.apply(0.5f);
        float negative = StickResponse.apply(-0.5f);

        assertTrue(positive > 0f);
        assertTrue(positive < 0.5f);
        assertEquals(-positive, negative, 0.0001f);
        assertEquals(1f, StickResponse.apply(1f), 0.0001f);
        assertEquals(-1f, StickResponse.apply(-1f), 0.0001f);
    }

    @Test
    public void rejectsNonFiniteInput() {
        assertEquals(0f, StickResponse.apply(Float.NaN), 0f);
        assertEquals(0f, StickResponse.apply(Float.POSITIVE_INFINITY), 0f);
    }
}
