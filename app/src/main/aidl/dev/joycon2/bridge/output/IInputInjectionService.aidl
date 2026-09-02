package dev.joycon2.bridge.output;

interface IInputInjectionService {
    void destroy() = 16777114;

    String probe() = 1;
    String setBridgeMode(int mode) = 2;
    String getStatus() = 3;
    int getActiveMode() = 4;
    int getDeviceMask() = 5;
    boolean isGrabbed() = 6;
    long getReportCount() = 7;
    void stopBridge() = 8;
    void setButtonSwaps(boolean swapAB, boolean swapXY) = 9;
    void setCompatButtonSwaps(boolean leftSwapAB, boolean leftSwapXY,
            boolean rightSwapAB, boolean rightSwapXY) = 10;
    String testRumble() = 11;
}
