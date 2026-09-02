package dev.joycon2.bridge.protocol;

public enum JoyCon2Button {
    Y(0, "Y"),
    X(1, "X"),
    B(2, "B"),
    A(3, "A"),
    RIGHT_SR(4, "R-SR"),
    RIGHT_SL(5, "R-SL"),
    R(6, "R"),
    ZR(7, "ZR"),
    MINUS(8, "−"),
    PLUS(9, "+"),
    RIGHT_STICK(10, "R3"),
    LEFT_STICK(11, "L3"),
    HOME(12, "Home"),
    CAPTURE(13, "Capture"),
    C(14, "C"),
    DPAD_DOWN(16, "↓"),
    DPAD_UP(17, "↑"),
    DPAD_RIGHT(18, "→"),
    DPAD_LEFT(19, "←"),
    LEFT_SR(20, "L-SR"),
    LEFT_SL(21, "L-SL"),
    L(22, "L"),
    ZL(23, "ZL"),
    GRIP_RIGHT(24, "GR"),
    GRIP_LEFT(25, "GL");

    private final long mask;
    private final String label;

    JoyCon2Button(int bit, String label) {
        this.mask = 1L << bit;
        this.label = label;
    }

    public long mask() {
        return mask;
    }

    public String label() {
        return label;
    }
}
