package dev.joycon2.bridge.output;

import java.util.HashSet;
import java.util.Set;

/** Maps hid-nintendo evdev nodes into combined or sideways gamepads. */
public final class JoyConEvdevMapper {
    public static final int SIDE_LEFT = 1;
    public static final int SIDE_RIGHT = 2;

    public static final int EV_SYN = 0;
    public static final int EV_KEY = 1;
    public static final int EV_ABS = 3;
    public static final int SYN_REPORT = 0;
    public static final int SYN_DROPPED = 3;

    public static final int ABS_X = 0x00;
    public static final int ABS_Y = 0x01;
    public static final int ABS_RX = 0x03;
    public static final int ABS_RY = 0x04;

    public static final int BTN_SOUTH = 0x130;
    public static final int BTN_EAST = 0x131;
    public static final int BTN_NORTH = 0x133;
    public static final int BTN_WEST = 0x134;
    public static final int BTN_Z = 0x135;
    public static final int BTN_TL = 0x136;
    public static final int BTN_TR = 0x137;
    public static final int BTN_TL2 = 0x138;
    public static final int BTN_TR2 = 0x139;
    public static final int BTN_SELECT = 0x13a;
    public static final int BTN_START = 0x13b;
    public static final int BTN_MODE = 0x13c;
    public static final int BTN_THUMBL = 0x13d;
    public static final int BTN_THUMBR = 0x13e;
    public static final int BTN_DPAD_UP = 0x220;
    public static final int BTN_DPAD_DOWN = 0x221;
    public static final int BTN_DPAD_LEFT = 0x222;
    public static final int BTN_DPAD_RIGHT = 0x223;

    private static final float OUTPUT_DEAD_ZONE = 0.08f;

    private static final int[] LEFT_KEYS = {
            BTN_Z, BTN_TL, BTN_TR, BTN_TL2, BTN_TR2, BTN_SELECT, BTN_THUMBL,
            BTN_DPAD_UP, BTN_DPAD_DOWN, BTN_DPAD_LEFT, BTN_DPAD_RIGHT
    };
    private static final int[] RIGHT_KEYS = {
            BTN_SOUTH, BTN_EAST, BTN_NORTH, BTN_WEST,
            BTN_TL, BTN_TR, BTN_TL2, BTN_TR2, BTN_START, BTN_MODE, BTN_THUMBR
    };

    private final SideState left = new SideState();
    private final SideState right = new SideState();

    public static int[] keysForSide(int side) {
        return (side == SIDE_LEFT ? LEFT_KEYS : RIGHT_KEYS).clone();
    }

    public void reset() {
        left.reset();
        right.reset();
    }

    public void setAxis(int side, int code, int value, int minimum, int maximum, int flat) {
        SideState state = state(side);
        Axis axis = state.axis(code);
        if (axis != null) {
            axis.configure(value, minimum, maximum, flat);
        }
    }

    public void setKey(int side, int code, boolean pressed) {
        SideState state = state(side);
        if (pressed) {
            state.keys.add(code);
        } else {
            state.keys.remove(code);
        }
    }

    public boolean applyEvent(int side, int type, int code, int value) {
        if (type == EV_KEY) {
            setKey(side, code, value != 0);
            return true;
        }
        if (type == EV_ABS) {
            Axis axis = state(side).axis(code);
            if (axis != null) {
                axis.value = value;
                return true;
            }
        }
        return false;
    }

    public MappedGamepad combined() {
        return combined(false, false);
    }

    /** Combined controller with optional Nintendo/Android face-button pair correction. */
    public MappedGamepad combined(boolean swapAB, boolean swapXY) {
        int buttons = 0;
        buttons = add(buttons, right, swapAB ? BTN_SOUTH : BTN_EAST, MappedGamepad.A);
        buttons = add(buttons, right, swapAB ? BTN_EAST : BTN_SOUTH, MappedGamepad.B);
        buttons = add(buttons, right, swapXY ? BTN_WEST : BTN_NORTH, MappedGamepad.X);
        buttons = add(buttons, right, swapXY ? BTN_NORTH : BTN_WEST, MappedGamepad.Y);
        buttons = add(buttons, left, BTN_TL, MappedGamepad.L1);
        buttons = add(buttons, right, BTN_TR, MappedGamepad.R1);
        buttons = add(buttons, left, BTN_TL2, MappedGamepad.L2);
        buttons = add(buttons, right, BTN_TR2, MappedGamepad.R2);
        buttons = add(buttons, left, BTN_SELECT, MappedGamepad.SELECT);
        buttons = add(buttons, right, BTN_START, MappedGamepad.START);
        buttons = add(buttons, right, BTN_MODE, MappedGamepad.MODE);
        buttons = add(buttons, left, BTN_Z, MappedGamepad.Z);
        buttons = add(buttons, left, BTN_THUMBL, MappedGamepad.THUMBL);
        buttons = add(buttons, right, BTN_THUMBR, MappedGamepad.THUMBR);

        return new MappedGamepad(
                buttons,
                hat(left),
                left.x.normalized(),
                left.y.normalized(),
                right.rx.normalized(),
                right.ry.normalized(),
                left.keys.contains(BTN_TL2) ? 1f : 0f,
                right.keys.contains(BTN_TR2) ? 1f : 0f
        );
    }

    /** Left Joy-Con rotated counter-clockwise, with its rail along the top. */
    public MappedGamepad sidewaysLeft() {
        return sidewaysLeft(false, false);
    }

    /** Left sideways controller with independently selectable AB and XY swaps. */
    public MappedGamepad sidewaysLeft(boolean swapAB, boolean swapXY) {
        int buttons = 0;
        buttons = add(buttons, left,
                swapAB ? BTN_DPAD_LEFT : BTN_DPAD_DOWN, MappedGamepad.A);
        buttons = add(buttons, left,
                swapAB ? BTN_DPAD_DOWN : BTN_DPAD_LEFT, MappedGamepad.B);
        buttons = add(buttons, left,
                swapXY ? BTN_DPAD_UP : BTN_DPAD_RIGHT, MappedGamepad.X);
        buttons = add(buttons, left,
                swapXY ? BTN_DPAD_RIGHT : BTN_DPAD_UP, MappedGamepad.Y);
        buttons = add(buttons, left, BTN_TR, MappedGamepad.L1);   // SL
        buttons = add(buttons, left, BTN_TR2, MappedGamepad.R1); // SR
        buttons = add(buttons, left, BTN_TL, MappedGamepad.L2);  // L
        buttons = add(buttons, left, BTN_TL2, MappedGamepad.R2); // ZL
        buttons = add(buttons, left, BTN_SELECT, MappedGamepad.SELECT);
        buttons = add(buttons, left, BTN_Z, MappedGamepad.MODE);
        buttons = add(buttons, left, BTN_THUMBL, MappedGamepad.THUMBL);

        return new MappedGamepad(
                buttons,
                8,
                left.y.normalized(),
                -left.x.normalized(),
                0f,
                0f,
                left.keys.contains(BTN_TL) ? 1f : 0f,
                left.keys.contains(BTN_TL2) ? 1f : 0f
        );
    }

    /** Right Joy-Con rotated clockwise, with its rail along the top. */
    public MappedGamepad sidewaysRight() {
        return sidewaysRight(false, false);
    }

    /** Right sideways controller with independently selectable AB and XY swaps. */
    public MappedGamepad sidewaysRight(boolean swapAB, boolean swapXY) {
        int buttons = 0;
        buttons = add(buttons, right,
                swapAB ? BTN_EAST : BTN_NORTH, MappedGamepad.A);
        buttons = add(buttons, right,
                swapAB ? BTN_NORTH : BTN_EAST, MappedGamepad.B);
        buttons = add(buttons, right,
                swapXY ? BTN_SOUTH : BTN_WEST, MappedGamepad.X);
        buttons = add(buttons, right,
                swapXY ? BTN_WEST : BTN_SOUTH, MappedGamepad.Y);
        buttons = add(buttons, right, BTN_TL, MappedGamepad.L1);   // SL
        buttons = add(buttons, right, BTN_TL2, MappedGamepad.R1); // SR
        buttons = add(buttons, right, BTN_TR, MappedGamepad.L2);  // R
        buttons = add(buttons, right, BTN_TR2, MappedGamepad.R2); // ZR
        buttons = add(buttons, right, BTN_START, MappedGamepad.START);
        buttons = add(buttons, right, BTN_MODE, MappedGamepad.MODE);
        buttons = add(buttons, right, BTN_THUMBR, MappedGamepad.THUMBL);

        return new MappedGamepad(
                buttons,
                8,
                -right.ry.normalized(),
                right.rx.normalized(),
                0f,
                0f,
                right.keys.contains(BTN_TR) ? 1f : 0f,
                right.keys.contains(BTN_TR2) ? 1f : 0f
        );
    }

    private SideState state(int side) {
        if (side == SIDE_LEFT) {
            return left;
        }
        if (side == SIDE_RIGHT) {
            return right;
        }
        throw new IllegalArgumentException("Unknown Joy-Con side: " + side);
    }

    private static int add(int buttons, SideState state, int evdevCode, int mappedButton) {
        return state.keys.contains(evdevCode) ? buttons | mappedButton : buttons;
    }

    private static int hat(SideState state) {
        int x = (state.keys.contains(BTN_DPAD_RIGHT) ? 1 : 0)
                - (state.keys.contains(BTN_DPAD_LEFT) ? 1 : 0);
        int y = (state.keys.contains(BTN_DPAD_DOWN) ? 1 : 0)
                - (state.keys.contains(BTN_DPAD_UP) ? 1 : 0);
        if (x == 0 && y < 0) return 0;
        if (x > 0 && y < 0) return 1;
        if (x > 0 && y == 0) return 2;
        if (x > 0) return 3;
        if (x == 0 && y > 0) return 4;
        if (x < 0 && y > 0) return 5;
        if (x < 0 && y == 0) return 6;
        if (x < 0) return 7;
        return 8;
    }

    private static final class SideState {
        final Set<Integer> keys = new HashSet<>();
        final Axis x = new Axis();
        final Axis y = new Axis();
        final Axis rx = new Axis();
        final Axis ry = new Axis();

        Axis axis(int code) {
            return switch (code) {
                case ABS_X -> x;
                case ABS_Y -> y;
                case ABS_RX -> rx;
                case ABS_RY -> ry;
                default -> null;
            };
        }

        void reset() {
            keys.clear();
            x.reset();
            y.reset();
            rx.reset();
            ry.reset();
        }
    }

    private static final class Axis {
        int value;
        int minimum = -32767;
        int maximum = 32767;
        int flat;

        void configure(int value, int minimum, int maximum, int flat) {
            this.value = value;
            this.minimum = minimum;
            this.maximum = maximum;
            this.flat = Math.max(0, flat);
        }

        void reset() {
            value = 0;
            minimum = -32767;
            maximum = 32767;
            flat = 0;
        }

        float normalized() {
            float center = (minimum + maximum) / 2f;
            float raw = value - center;
            float range = raw < 0f ? center - minimum : maximum - center;
            if (range <= 0f) {
                return 0f;
            }
            float normalized = raw / range;
            float deadZone = Math.max(OUTPUT_DEAD_ZONE, flat / range);
            float magnitude = Math.abs(normalized);
            if (magnitude <= deadZone) {
                return 0f;
            }
            float scaled = (magnitude - deadZone) / (1f - deadZone);
            return Math.copySign(Math.min(1f, scaled), normalized);
        }
    }
}
