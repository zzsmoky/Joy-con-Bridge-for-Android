package dev.joycon2.bridge.output;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import android.view.KeyEvent;

import org.junit.Test;

import java.util.List;

import dev.joycon2.bridge.protocol.JoyCon2Button;
import dev.joycon2.bridge.protocol.JoyCon2InputState;
import dev.joycon2.bridge.protocol.JoyCon2ReportDecoder;

public final class GamepadStateTest {
    private final JoyCon2ReportDecoder decoder = new JoyCon2ReportDecoder();

    @Test
    public void combinesSidesAndConvertsNintendoYAxis() {
        JoyCon2InputState left = decode(inputReport(
                746,
                3249,
                0x7FF,
                0x7FF,
                JoyCon2Button.DPAD_UP.mask() | JoyCon2Button.ZL.mask()
        ));
        JoyCon2InputState right = decode(inputReport(
                0x7FF,
                0x7FF,
                3249,
                746,
                JoyCon2Button.A.mask() | JoyCon2Button.ZR.mask()
        ));

        GamepadState state = GamepadState.combine(left, right);

        assertEquals(-1f, state.leftX(), 0.0001f);
        assertEquals(-1f, state.leftY(), 0.0001f);
        assertEquals(1f, state.rightX(), 0.0001f);
        assertEquals(1f, state.rightY(), 0.0001f);
        assertEquals(-1f, state.hatY(), 0.0001f);
        assertEquals(1f, state.leftTrigger(), 0.0001f);
        assertEquals(1f, state.rightTrigger(), 0.0001f);
        assertTrue(state.isPressed(JoyCon2Button.A));
    }

    @Test
    public void emitsOnlyChangedMappedButtons() {
        JoyCon2InputState right = decode(inputReport(
                0x7FF,
                0x7FF,
                1998,
                1998,
                JoyCon2Button.A.mask() | JoyCon2Button.RIGHT_SR.mask()
        ));
        GamepadState pressed = GamepadState.combine(null, right);

        List<AndroidGamepadMapping.KeyTransition> down =
                AndroidGamepadMapping.transitions(GamepadState.neutral(), pressed);
        assertEquals(1, down.size());
        assertEquals(KeyEvent.KEYCODE_BUTTON_A, down.get(0).keyCode());
        assertTrue(down.get(0).pressed());

        List<AndroidGamepadMapping.KeyTransition> up =
                AndroidGamepadMapping.transitions(pressed, GamepadState.neutral());
        assertEquals(1, up.size());
        assertFalse(up.get(0).pressed());
    }

    private JoyCon2InputState decode(byte[] report) {
        return decoder.decode(report).orElseThrow();
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
