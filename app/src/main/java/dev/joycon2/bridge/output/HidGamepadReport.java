package dev.joycon2.bridge.output;

import dev.joycon2.bridge.protocol.JoyCon2Button;

/** Encodes the merged Joy-Con state as the input report described by HidGamepadDescriptor. */
public final class HidGamepadReport {
    public static final int REPORT_ID = 1;
    public static final int REPORT_LENGTH = 10;

    private HidGamepadReport() {
    }

    public static byte[] neutral() {
        return encode(GamepadState.neutral());
    }

    public static byte[] neutralMapped() {
        return encode(MappedGamepad.neutral());
    }

    public static byte[] encode(MappedGamepad state) {
        MappedGamepad value = state == null ? MappedGamepad.neutral() : state;
        byte[] report = new byte[REPORT_LENGTH];
        report[0] = REPORT_ID;
        report[1] = (byte) value.buttons();
        report[2] = (byte) (value.buttons() >>> 8);
        report[3] = (byte) value.hat();
        report[4] = (byte) axis(value.leftX());
        report[5] = (byte) axis(value.leftY());
        report[6] = (byte) axis(value.rightX());
        report[7] = (byte) axis(value.rightY());
        report[8] = (byte) trigger(value.leftTrigger());
        report[9] = (byte) trigger(value.rightTrigger());
        return report;
    }

    public static byte[] encode(GamepadState state) {
        GamepadState value = state == null ? GamepadState.neutral() : state;
        int buttons = 0;
        buttons = withButton(buttons, value, JoyCon2Button.A, 1);
        buttons = withButton(buttons, value, JoyCon2Button.B, 2);
        buttons = withButton(buttons, value, JoyCon2Button.C, 3);
        buttons = withButton(buttons, value, JoyCon2Button.X, 4);
        buttons = withButton(buttons, value, JoyCon2Button.Y, 5);
        buttons = withButton(buttons, value, JoyCon2Button.CAPTURE, 6);
        buttons = withButton(buttons, value, JoyCon2Button.L, 7);
        buttons = withButton(buttons, value, JoyCon2Button.R, 8);
        buttons = withButton(buttons, value, JoyCon2Button.ZL, 9);
        buttons = withButton(buttons, value, JoyCon2Button.ZR, 10);
        buttons = withButton(buttons, value, JoyCon2Button.MINUS, 11);
        buttons = withButton(buttons, value, JoyCon2Button.PLUS, 12);
        buttons = withButton(buttons, value, JoyCon2Button.HOME, 13);
        buttons = withButton(buttons, value, JoyCon2Button.LEFT_STICK, 14);
        buttons = withButton(buttons, value, JoyCon2Button.RIGHT_STICK, 15);

        byte[] report = new byte[REPORT_LENGTH];
        report[0] = REPORT_ID;
        report[1] = (byte) buttons;
        report[2] = (byte) (buttons >>> 8);
        report[3] = (byte) hat(value.hatX(), value.hatY());
        report[4] = (byte) axis(value.leftX());
        report[5] = (byte) axis(value.leftY());
        report[6] = (byte) axis(value.rightX());
        report[7] = (byte) axis(value.rightY());
        report[8] = (byte) trigger(value.leftTrigger());
        report[9] = (byte) trigger(value.rightTrigger());
        return report;
    }

    private static int withButton(
            int buttons,
            GamepadState state,
            JoyCon2Button button,
            int hidUsage
    ) {
        return state.isPressed(button) ? buttons | (1 << (hidUsage - 1)) : buttons;
    }

    private static int hat(float x, float y) {
        int horizontal = Float.compare(x, 0f);
        int vertical = Float.compare(y, 0f);
        if (horizontal == 0 && vertical < 0) {
            return 0;
        }
        if (horizontal > 0 && vertical < 0) {
            return 1;
        }
        if (horizontal > 0 && vertical == 0) {
            return 2;
        }
        if (horizontal > 0) {
            return 3;
        }
        if (horizontal == 0 && vertical > 0) {
            return 4;
        }
        if (horizontal < 0 && vertical > 0) {
            return 5;
        }
        if (horizontal < 0 && vertical == 0) {
            return 6;
        }
        if (horizontal < 0) {
            return 7;
        }
        return 8;
    }

    private static int axis(float value) {
        float clamped = Math.max(-1f, Math.min(1f, value));
        return Math.round((clamped + 1f) * 127.5f);
    }

    private static int trigger(float value) {
        float clamped = Math.max(0f, Math.min(1f, value));
        return Math.round(clamped * 255f);
    }
}
