package dev.joycon2.bridge.protocol;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.Optional;

public final class JoyCon2ReportDecoderTest {
    private final JoyCon2ReportDecoder decoder = new JoyCon2ReportDecoder();

    @Test
    public void decodesButtonsAndPackedSticks() {
        byte[] report = inputReport(1998, 1000, 2047, 0, (1L << 3) | (1L << 17));

        JoyCon2InputState state = decoder.decode(report).orElseThrow();

        assertTrue(state.isPressed(JoyCon2Button.A));
        assertTrue(state.isPressed(JoyCon2Button.DPAD_UP));
        assertFalse(state.isPressed(JoyCon2Button.B));
        assertEquals(1998, state.leftX());
        assertEquals(1000, state.leftY());
        assertEquals(1998, state.rightX());
        assertEquals(1998, state.rightY());
        assertEquals(JoyCon2Side.LEFT, state.inferredSide());
    }

    @Test
    public void acceptsOptionalA1Prefix() {
        byte[] report = inputReport(746, 3249, 1998, 1998, 1L << 7);
        byte[] prefixed = new byte[report.length + 1];
        prefixed[0] = (byte) 0xA1;
        System.arraycopy(report, 0, prefixed, 1, report.length);

        JoyCon2InputState state = decoder.decode(prefixed).orElseThrow();

        assertTrue(state.hadAttPrefix());
        assertTrue(state.isPressed(JoyCon2Button.ZR));
        assertEquals(-1f, state.normalizedLeftX(), 0.0001f);
        assertEquals(1f, state.normalizedLeftY(), 0.0001f);
    }

    @Test
    public void doesNotTreatRollingA1AsPrefixInNativeBleReport() {
        byte[] report = inputReport(
                746,
                3249,
                0x7FF,
                0x7FF,
                JoyCon2Button.DPAD_UP.mask() | JoyCon2Button.ZL.mask()
        );
        report[0] = (byte) 0xA1;

        JoyCon2InputState state = decoder.decode(report).orElseThrow();

        assertFalse(state.hadAttPrefix());
        assertTrue(state.isPressed(JoyCon2Button.DPAD_UP));
        assertTrue(state.isPressed(JoyCon2Button.ZL));
        assertFalse(state.isPressed(JoyCon2Button.DPAD_RIGHT));
        assertEquals(-1f, state.normalizedLeftX(), 0.0001f);
        assertEquals(1f, state.normalizedLeftY(), 0.0001f);
    }

    @Test
    public void rejectsShortAndSubcommandReports() {
        assertTrue(decoder.decode(new byte[7]).isEmpty());
        byte[] subcommand = new byte[16];
        subcommand[0] = 0x21;
        Optional<JoyCon2InputState> result = decoder.decode(subcommand);
        assertTrue(result.isEmpty());
    }

    @Test
    public void identifiesControllerSideFromAdvertisement() {
        assertEquals(
                JoyCon2Side.LEFT,
                JoyCon2Protocol.sideFromManufacturerData(
                        new byte[]{0x01, 0x00, 0x03, 0x7E, 0x06, 0x20}
                )
        );
        assertEquals(
                JoyCon2Side.RIGHT,
                JoyCon2Protocol.sideFromManufacturerData(
                        new byte[]{0x01, 0x00, 0x03, 0x7E, 0x07, 0x20}
                )
        );
    }

    @Test
    public void infersRightSideFromActiveStickHalf() {
        JoyCon2InputState state = decoder.decode(
                inputReport(0x7FF, 0x7FF, 1605, 3259, (1L << 6) | (1L << 7))
        ).orElseThrow();

        assertEquals(JoyCon2Side.RIGHT, state.inferredSide());
        assertTrue(state.isPressed(JoyCon2Button.R));
        assertTrue(state.isPressed(JoyCon2Button.ZR));
    }

    private static byte[] inputReport(int lx, int ly, int rx, int ry, long buttons) {
        byte[] report = new byte[62];
        report[0] = 0x08;
        report[4] = (byte) buttons;
        report[5] = (byte) (buttons >> 8);
        report[6] = (byte) (buttons >> 16);
        report[7] = (byte) (buttons >> 24);
        packStick(report, 10, lx, ly);
        packStick(report, 13, rx, ry);
        return report;
    }

    private static void packStick(byte[] target, int offset, int x, int y) {
        target[offset] = (byte) x;
        target[offset + 1] = (byte) (((x >> 8) & 0x0F) | ((y & 0x0F) << 4));
        target[offset + 2] = (byte) (y >> 4);
    }
}
