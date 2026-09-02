package dev.joycon2.bridge.output;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

public final class JoyConRumbleControllerTest {
    @Test
    public void encodesNeutralMediumAndMaximumNintendoAmplitude() {
        assertEquals("00014040", JoyConRumbleController.motorData(0));
        assertEquals("00414050", JoyConRumbleController.motorData(15_000));
        assertEquals("00C94072", JoyConRumbleController.motorData(65_535));
    }

    @Test
    public void clampsMagnitudeToUnsignedSixteenBitRange() {
        assertEquals("00014040", JoyConRumbleController.motorData(-1));
        assertEquals("00C94072", JoyConRumbleController.motorData(100_000));
    }
}
