package dev.joycon2.bridge.transport;

import dev.joycon2.bridge.protocol.JoyCon2InputState;
import dev.joycon2.bridge.protocol.JoyCon2Side;

public final class ControllerSnapshot {
    private final String address;
    private final String name;
    private final JoyCon2Side side;
    private final ConnectionStage stage;
    private final String detail;
    private final int rssi;
    private final long frameCount;
    private final double reportRateHz;
    private final JoyCon2InputState input;
    private final String lastReportHex;

    ControllerSnapshot(
            String address,
            String name,
            JoyCon2Side side,
            ConnectionStage stage,
            String detail,
            int rssi,
            long frameCount,
            double reportRateHz,
            JoyCon2InputState input,
            String lastReportHex
    ) {
        this.address = address;
        this.name = name;
        this.side = side;
        this.stage = stage;
        this.detail = detail;
        this.rssi = rssi;
        this.frameCount = frameCount;
        this.reportRateHz = reportRateHz;
        this.input = input;
        this.lastReportHex = lastReportHex;
    }

    public String address() {
        return address;
    }

    public String name() {
        return name;
    }

    public JoyCon2Side side() {
        return side;
    }

    public ConnectionStage stage() {
        return stage;
    }

    public String detail() {
        return detail;
    }

    public int rssi() {
        return rssi;
    }

    public long frameCount() {
        return frameCount;
    }

    public double reportRateHz() {
        return reportRateHz;
    }

    public JoyCon2InputState input() {
        return input;
    }

    public String lastReportHex() {
        return lastReportHex;
    }
}
