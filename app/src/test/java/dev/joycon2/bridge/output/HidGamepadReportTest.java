package dev.joycon2.bridge.output;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import dev.joycon2.bridge.protocol.JoyCon2Button;
import dev.joycon2.bridge.protocol.JoyCon2InputState;
import dev.joycon2.bridge.protocol.JoyCon2ReportDecoder;

public final class HidGamepadReportTest {
    private final JoyCon2ReportDecoder decoder = new JoyCon2ReportDecoder();

    @Test
    public void neutralReportCentersSticksAndHat() {
        assertArrayEquals(
                new int[]{1, 0, 0, 8, 128, 128, 128, 128, 0, 0},
                unsigned(HidGamepadReport.neutral())
        );
    }

    @Test
    public void encodesButtonsHatSticksAndTriggers() {
        JoyCon2InputState left = decode(inputReport(
                746,
                3249,
                0x7FF,
                0x7FF,
                JoyCon2Button.DPAD_UP.mask()
                        | JoyCon2Button.DPAD_RIGHT.mask()
                        | JoyCon2Button.L.mask()
                        | JoyCon2Button.ZL.mask()
                        | JoyCon2Button.MINUS.mask()
                        | JoyCon2Button.LEFT_STICK.mask()
        ));
        JoyCon2InputState right = decode(inputReport(
                0x7FF,
                0x7FF,
                3249,
                746,
                JoyCon2Button.A.mask()
                        | JoyCon2Button.C.mask()
                        | JoyCon2Button.CAPTURE.mask()
                        | JoyCon2Button.ZR.mask()
                        | JoyCon2Button.PLUS.mask()
                        | JoyCon2Button.HOME.mask()
                        | JoyCon2Button.RIGHT_STICK.mask()
        ));

        assertArrayEquals(
                new int[]{1, 101, 127, 1, 0, 0, 255, 255, 255, 255},
                unsigned(HidGamepadReport.encode(GamepadState.combine(left, right)))
        );
    }

    @Test
    public void commandsUseUnsignedDecimalByteArrays() {
        String register = HidGamepadDescriptor.registerCommand();
        assertTrue(register.contains("\"command\":\"register\""));
        assertTrue(register.contains("\"name\":\"Joy-Con 2 Bridge Virtual Gamepad\""));
        assertTrue(register.contains("\"descriptor\":[5,1,9,5"));

        String report = HidGamepadDescriptor.reportCommand(HidGamepadReport.neutral());
        assertEquals(
                "{\"id\":2202,\"command\":\"report\","
                        + "\"report\":[1,0,0,8,128,128,128,128,0,0]}",
                report
        );
    }

    private JoyCon2InputState decode(byte[] report) {
        return decoder.decode(report).orElseThrow();
    }

    private static int[] unsigned(byte[] values) {
        int[] result = new int[values.length];
        for (int index = 0; index < values.length; index++) {
            result[index] = Byte.toUnsignedInt(values[index]);
        }
        return result;
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
