package dev.joycon2.bridge.output;

import android.view.KeyEvent;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import dev.joycon2.bridge.protocol.JoyCon2Button;

/** Maps Joy-Con labels to Android's standard gamepad key codes. */
public final class AndroidGamepadMapping {
    public static final class KeyTransition {
        private final int keyCode;
        private final boolean pressed;

        KeyTransition(int keyCode, boolean pressed) {
            this.keyCode = keyCode;
            this.pressed = pressed;
        }

        public int keyCode() {
            return keyCode;
        }

        public boolean pressed() {
            return pressed;
        }
    }

    private AndroidGamepadMapping() {
    }

    public static List<KeyTransition> transitions(GamepadState before, GamepadState after) {
        GamepadState previous = before == null ? GamepadState.neutral() : before;
        GamepadState next = after == null ? GamepadState.neutral() : after;
        List<KeyTransition> result = new ArrayList<>();
        for (JoyCon2Button button : JoyCon2Button.values()) {
            int keyCode = keyCodeFor(button);
            if (keyCode == KeyEvent.KEYCODE_UNKNOWN) {
                continue;
            }
            boolean wasPressed = previous.isPressed(button);
            boolean isPressed = next.isPressed(button);
            if (wasPressed != isPressed) {
                result.add(new KeyTransition(keyCode, isPressed));
            }
        }
        return Collections.unmodifiableList(result);
    }

    public static int keyCodeFor(JoyCon2Button button) {
        return switch (button) {
            case A -> KeyEvent.KEYCODE_BUTTON_A;
            case B -> KeyEvent.KEYCODE_BUTTON_B;
            case X -> KeyEvent.KEYCODE_BUTTON_X;
            case Y -> KeyEvent.KEYCODE_BUTTON_Y;
            case L -> KeyEvent.KEYCODE_BUTTON_L1;
            case R -> KeyEvent.KEYCODE_BUTTON_R1;
            case ZL -> KeyEvent.KEYCODE_BUTTON_L2;
            case ZR -> KeyEvent.KEYCODE_BUTTON_R2;
            case LEFT_STICK -> KeyEvent.KEYCODE_BUTTON_THUMBL;
            case RIGHT_STICK -> KeyEvent.KEYCODE_BUTTON_THUMBR;
            case PLUS -> KeyEvent.KEYCODE_BUTTON_START;
            case MINUS -> KeyEvent.KEYCODE_BUTTON_SELECT;
            case HOME -> KeyEvent.KEYCODE_BUTTON_MODE;
            case C -> KeyEvent.KEYCODE_BUTTON_C;
            case CAPTURE -> KeyEvent.KEYCODE_BUTTON_1;
            case DPAD_UP -> KeyEvent.KEYCODE_DPAD_UP;
            case DPAD_DOWN -> KeyEvent.KEYCODE_DPAD_DOWN;
            case DPAD_LEFT -> KeyEvent.KEYCODE_DPAD_LEFT;
            case DPAD_RIGHT -> KeyEvent.KEYCODE_DPAD_RIGHT;
            default -> KeyEvent.KEYCODE_UNKNOWN;
        };
    }
}
