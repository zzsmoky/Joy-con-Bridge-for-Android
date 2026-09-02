package dev.joycon2.bridge.protocol;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public final class JoyCon2InputState {
    public static final int STICK_MIN = 746;
    public static final int STICK_CENTER = 1998;
    public static final int STICK_MAX = 3249;
    public static final int DEFAULT_DEAD_ZONE = 90;

    private final long buttons;
    private final int rawLeftX;
    private final int rawLeftY;
    private final int rawRightX;
    private final int rawRightY;
    private final int leftX;
    private final int leftY;
    private final int rightX;
    private final int rightY;
    private final boolean hadAttPrefix;

    JoyCon2InputState(
            long buttons,
            int rawLeftX,
            int rawLeftY,
            int rawRightX,
            int rawRightY,
            int leftX,
            int leftY,
            int rightX,
            int rightY,
            boolean hadAttPrefix
    ) {
        this.buttons = buttons;
        this.rawLeftX = rawLeftX;
        this.rawLeftY = rawLeftY;
        this.rawRightX = rawRightX;
        this.rawRightY = rawRightY;
        this.leftX = leftX;
        this.leftY = leftY;
        this.rightX = rightX;
        this.rightY = rightY;
        this.hadAttPrefix = hadAttPrefix;
    }

    public long buttons() {
        return buttons;
    }

    public int rawLeftX() {
        return rawLeftX;
    }

    public int rawLeftY() {
        return rawLeftY;
    }

    public int rawRightX() {
        return rawRightX;
    }

    public int rawRightY() {
        return rawRightY;
    }

    public int leftX() {
        return leftX;
    }

    public int leftY() {
        return leftY;
    }

    public int rightX() {
        return rightX;
    }

    public int rightY() {
        return rightY;
    }

    public boolean hadAttPrefix() {
        return hadAttPrefix;
    }

    /**
     * A single Joy-Con 2 reports only its own stick. The absent stick is encoded
     * as zero or 0x7FF, so live input is a more reliable side signal than some
     * platform-specific BLE advertisement layouts.
     */
    public JoyCon2Side inferredSide() {
        boolean leftStickPresent = axisPresent(rawLeftX) || axisPresent(rawLeftY);
        boolean rightStickPresent = axisPresent(rawRightX) || axisPresent(rawRightY);
        if (leftStickPresent == rightStickPresent) {
            return JoyCon2Side.UNKNOWN;
        }
        return leftStickPresent ? JoyCon2Side.LEFT : JoyCon2Side.RIGHT;
    }

    public boolean isPressed(JoyCon2Button button) {
        return (buttons & button.mask()) != 0;
    }

    public List<String> pressedButtonLabels() {
        List<String> labels = new ArrayList<>();
        for (JoyCon2Button button : JoyCon2Button.values()) {
            if (isPressed(button)) {
                labels.add(button.label());
            }
        }
        return Collections.unmodifiableList(labels);
    }

    public float normalizedLeftX() {
        return normalize(leftX);
    }

    public float normalizedLeftY() {
        return normalize(leftY);
    }

    public float normalizedRightX() {
        return normalize(rightX);
    }

    public float normalizedRightY() {
        return normalize(rightY);
    }

    static float normalize(int value) {
        int delta = value - STICK_CENTER;
        if (Math.abs(delta) <= DEFAULT_DEAD_ZONE) {
            return 0f;
        }
        float result = delta > 0
                ? delta / (float) (STICK_MAX - STICK_CENTER)
                : delta / (float) (STICK_CENTER - STICK_MIN);
        return Math.max(-1f, Math.min(1f, result));
    }

    private static boolean axisPresent(int rawValue) {
        return rawValue != 0 && rawValue != JoyCon2ReportDecoder.SENTINEL_STICK_VALUE;
    }
}
