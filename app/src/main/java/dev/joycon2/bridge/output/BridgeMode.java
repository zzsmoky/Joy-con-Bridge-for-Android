package dev.joycon2.bridge.output;

/** User-selectable bridge topology. Codes are part of the UserService AIDL contract. */
public enum BridgeMode {
    NATIVE_DUAL(0, "Native dual controllers"),
    COMBINED(1, "Combined controller"),
    COMPAT_DUAL(2, "Compatible dual controllers");

    private final int code;
    private final String label;

    BridgeMode(int code, String label) {
        this.code = code;
        this.label = label;
    }

    public int code() {
        return code;
    }

    public String label() {
        return label;
    }

    public static BridgeMode fromCode(int code) {
        for (BridgeMode mode : values()) {
            if (mode.code == code) {
                return mode;
            }
        }
        return NATIVE_DUAL;
    }
}
