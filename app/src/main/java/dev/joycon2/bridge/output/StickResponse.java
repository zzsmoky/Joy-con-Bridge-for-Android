package dev.joycon2.bridge.output;

/** Output-only dead zone and response curve tuned for the short Joy-Con 2 sticks. */
public final class StickResponse {
    public static final float DEAD_ZONE = 0.16f;
    public static final double CURVE_EXPONENT = 1.35;

    private StickResponse() {
    }

    public static float apply(float value) {
        if (!Float.isFinite(value)) {
            return 0f;
        }
        float clamped = Math.max(-1f, Math.min(1f, value));
        float magnitude = Math.abs(clamped);
        if (magnitude <= DEAD_ZONE) {
            return 0f;
        }
        float rescaled = (magnitude - DEAD_ZONE) / (1f - DEAD_ZONE);
        float curved = (float) Math.pow(rescaled, CURVE_EXPONENT);
        return Math.copySign(curved, clamped);
    }
}
