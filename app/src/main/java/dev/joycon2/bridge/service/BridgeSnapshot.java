package dev.joycon2.bridge.service;

import java.util.Collections;
import java.util.List;

import dev.joycon2.bridge.output.OutputSnapshot;

/** Complete immutable UI state emitted by the foreground bridge service. */
public final class BridgeSnapshot {
    private final List<String> logLines;
    private final OutputSnapshot output;
    private final boolean foreground;
    private final boolean swapAB;
    private final boolean swapXY;
    private final boolean compatLeftSwapAB;
    private final boolean compatLeftSwapXY;
    private final boolean compatRightSwapAB;
    private final boolean compatRightSwapXY;

    BridgeSnapshot(
            List<String> logLines,
            OutputSnapshot output,
            boolean foreground,
            boolean swapAB,
            boolean swapXY,
            boolean compatLeftSwapAB,
            boolean compatLeftSwapXY,
            boolean compatRightSwapAB,
            boolean compatRightSwapXY
    ) {
        this.logLines = List.copyOf(logLines);
        this.output = output;
        this.foreground = foreground;
        this.swapAB = swapAB;
        this.swapXY = swapXY;
        this.compatLeftSwapAB = compatLeftSwapAB;
        this.compatLeftSwapXY = compatLeftSwapXY;
        this.compatRightSwapAB = compatRightSwapAB;
        this.compatRightSwapXY = compatRightSwapXY;
    }

    public List<String> logLines() {
        return Collections.unmodifiableList(logLines);
    }

    public OutputSnapshot output() {
        return output;
    }

    public boolean foreground() {
        return foreground;
    }

    public boolean swapAB() {
        return swapAB;
    }

    public boolean swapXY() {
        return swapXY;
    }

    public boolean compatLeftSwapAB() {
        return compatLeftSwapAB;
    }

    public boolean compatLeftSwapXY() {
        return compatLeftSwapXY;
    }

    public boolean compatRightSwapAB() {
        return compatRightSwapAB;
    }

    public boolean compatRightSwapXY() {
        return compatRightSwapXY;
    }
}
