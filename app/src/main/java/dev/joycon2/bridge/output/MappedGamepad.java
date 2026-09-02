package dev.joycon2.bridge.output;

/** Immutable state matching Android's generic HID gamepad layout. */
public final class MappedGamepad {
    public static final int A = 1 << 0;
    public static final int B = 1 << 1;
    public static final int C = 1 << 2;
    public static final int X = 1 << 3;
    public static final int Y = 1 << 4;
    public static final int Z = 1 << 5;
    public static final int L1 = 1 << 6;
    public static final int R1 = 1 << 7;
    public static final int L2 = 1 << 8;
    public static final int R2 = 1 << 9;
    public static final int SELECT = 1 << 10;
    public static final int START = 1 << 11;
    public static final int MODE = 1 << 12;
    public static final int THUMBL = 1 << 13;
    public static final int THUMBR = 1 << 14;

    private static final MappedGamepad NEUTRAL = new MappedGamepad(
            0, 8, 0f, 0f, 0f, 0f, 0f, 0f
    );

    private final int buttons;
    private final int hat;
    private final float leftX;
    private final float leftY;
    private final float rightX;
    private final float rightY;
    private final float leftTrigger;
    private final float rightTrigger;

    public MappedGamepad(
            int buttons,
            int hat,
            float leftX,
            float leftY,
            float rightX,
            float rightY,
            float leftTrigger,
            float rightTrigger
    ) {
        this.buttons = buttons;
        this.hat = Math.max(0, Math.min(8, hat));
        this.leftX = clampAxis(leftX);
        this.leftY = clampAxis(leftY);
        this.rightX = clampAxis(rightX);
        this.rightY = clampAxis(rightY);
        this.leftTrigger = clampTrigger(leftTrigger);
        this.rightTrigger = clampTrigger(rightTrigger);
    }

    public static MappedGamepad neutral() {
        return NEUTRAL;
    }

    public int buttons() {
        return buttons;
    }

    public int hat() {
        return hat;
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

    public float leftTrigger() {
        return leftTrigger;
    }

    public float rightTrigger() {
        return rightTrigger;
    }

    private static float clampAxis(float value) {
        return Math.max(-1f, Math.min(1f, value));
    }

    private static float clampTrigger(float value) {
        return Math.max(0f, Math.min(1f, value));
    }
}
