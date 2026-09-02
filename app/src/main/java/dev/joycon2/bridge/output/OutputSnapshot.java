package dev.joycon2.bridge.output;

/** Immutable view of the privileged bridge session. */
public final class OutputSnapshot {
    private final OutputStage stage;
    private final BridgeMode mode;
    private final String detail;
    private final long injectedEvents;
    private final int shizukuUid;
    private final int deviceMask;
    private final boolean grabbed;

    public OutputSnapshot(
            OutputStage stage,
            BridgeMode mode,
            String detail,
            long injectedEvents,
            int shizukuUid,
            int deviceMask,
            boolean grabbed
    ) {
        this.stage = stage;
        this.mode = mode;
        this.detail = detail;
        this.injectedEvents = injectedEvents;
        this.shizukuUid = shizukuUid;
        this.deviceMask = deviceMask;
        this.grabbed = grabbed;
    }

    public static OutputSnapshot initial() {
        return new OutputSnapshot(
                OutputStage.SHIZUKU_STOPPED,
                BridgeMode.NATIVE_DUAL,
                "Waiting for Shizuku",
                0L,
                -1,
                0,
                false
        );
    }

    public OutputStage stage() {
        return stage;
    }

    public BridgeMode mode() {
        return mode;
    }

    public String detail() {
        return detail;
    }

    public long injectedEvents() {
        return injectedEvents;
    }

    public int shizukuUid() {
        return shizukuUid;
    }

    public int deviceMask() {
        return deviceMask;
    }

    public boolean grabbed() {
        return grabbed;
    }
}
