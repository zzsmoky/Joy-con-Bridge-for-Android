package dev.joycon2.bridge.output;

import dev.joycon2.bridge.protocol.JoyCon2Button;
import dev.joycon2.bridge.protocol.JoyCon2InputState;

/** Immutable Android-gamepad-shaped state produced from a left/right Joy-Con pair. */
public final class GamepadState {
    private static final GamepadState NEUTRAL = new GamepadState(0L, 0f, 0f, 0f, 0f);

    private final long buttons;
    private final float leftX;
    private final float leftY;
    private final float rightX;
    private final float rightY;

    private GamepadState(
            long buttons,
            float leftX,
            float leftY,
            float rightX,
            float rightY
    ) {
        this.buttons = buttons;
        this.leftX = clamp(leftX);
        this.leftY = clamp(leftY);
        this.rightX = clamp(rightX);
        this.rightY = clamp(rightY);
    }

    public static GamepadState neutral() {
        return NEUTRAL;
    }

    public static GamepadState combine(
            JoyCon2InputState left,
            JoyCon2InputState right
    ) {
        long buttons = (left == null ? 0L : left.buttons())
                | (right == null ? 0L : right.buttons());

        // Nintendo stick reports grow upward; Android joystick Y axes grow downward.
        float leftX = left == null ? 0f : StickResponse.apply(left.normalizedLeftX());
        float leftY = left == null ? 0f : StickResponse.apply(-left.normalizedLeftY());
        float rightX = right == null ? 0f : StickResponse.apply(right.normalizedRightX());
        float rightY = right == null ? 0f : StickResponse.apply(-right.normalizedRightY());
        return new GamepadState(buttons, leftX, leftY, rightX, rightY);
    }

    public long buttons() {
        return buttons;
    }

    public boolean isPressed(JoyCon2Button button) {
        return (buttons & button.mask()) != 0;
    }

    public float leftX() {
        return leftX;
    }

    public float leftY() {
        return leftY;
    }

    public float rightX() {
        return rightX;
    }

    public float rightY() {
        return rightY;
    }

    public float hatX() {
        float value = 0f;
        if (isPressed(JoyCon2Button.DPAD_LEFT)) {
            value -= 1f;
        }
        if (isPressed(JoyCon2Button.DPAD_RIGHT)) {
            value += 1f;
        }
        return value;
    }

    public float hatY() {
        float value = 0f;
        if (isPressed(JoyCon2Button.DPAD_UP)) {
            value -= 1f;
        }
        if (isPressed(JoyCon2Button.DPAD_DOWN)) {
            value += 1f;
        }
        return value;
    }

    public float leftTrigger() {
        return isPressed(JoyCon2Button.ZL) ? 1f : 0f;
    }

    public float rightTrigger() {
        return isPressed(JoyCon2Button.ZR) ? 1f : 0f;
    }

    private static float clamp(float value) {
        return Math.max(-1f, Math.min(1f, value));
    }
}
