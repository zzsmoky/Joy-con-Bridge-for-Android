package dev.joycon2.bridge.output;

import android.annotation.SuppressLint;
import android.content.Context;
import android.os.Process;
import android.os.SystemClock;
import android.os.VibrationEffect;
import android.os.Vibrator;
import android.util.Log;
import android.view.InputDevice;

import java.io.IOException;
import java.util.HashSet;
import java.util.Set;

/**
 * Shizuku shell process that owns the physical evdev grabs and Android uinput devices.
 * Closing this service always releases EVIOCGRAB and removes every virtual controller.
 */
@SuppressLint("PrivateApi")
public final class ShizukuInputService extends IInputInjectionService.Stub {
    private static final String FF_LOG_TAG = "JoyConBridgeFF";
    private static final long DEVICE_REGISTRATION_TIMEOUT_MS = 5_000L;
    private static final long PARTIAL_PAIR_RESCAN_MS = 1_500L;
    private static final int READ_TIMEOUT_MS = 200;
    private static final int BOTH_DEVICES = JoyConEvdevMapper.SIDE_LEFT
            | JoyConEvdevMapper.SIDE_RIGHT;

    static {
        System.loadLibrary("joycon_evdev");
    }

    private volatile int activeMode = BridgeMode.NATIVE_DUAL.code();
    private volatile int deviceMask;
    private volatile boolean grabbed;
    private volatile boolean inputRunning;
    private volatile boolean rumbleRunning;
    private volatile long sessionGeneration;
    private volatile long mappingRevision;
    private volatile long reportCount;
    private volatile boolean swapAB;
    private volatile boolean swapXY;
    private volatile boolean compatLeftSwapAB;
    private volatile boolean compatLeftSwapXY;
    private volatile boolean compatRightSwapAB;
    private volatile boolean compatRightSwapXY;
    private volatile String status = "Native dual controllers; checking Joy-Cons";
    private volatile String deviceDescription = "";
    private volatile String rumbleStatus = "";

    private Thread inputThread;
    private Thread combinedRumbleThread;
    private Thread leftRumbleThread;
    private Thread rightRumbleThread;
    private long combinedGamepad;
    private long leftGamepad;
    private long rightGamepad;
    private JoyConRumbleController rumbleController;
    private final Context serviceContext;

    public ShizukuInputService() {
        serviceContext = null;
    }

    @SuppressWarnings("unused")
    public ShizukuInputService(Context context) {
        serviceContext = context;
    }

    @Override
    public synchronized String probe() {
        String uinput = nativeUinputProbe();
        if (uinput.startsWith("Error:")) {
            return uinput;
        }
        String devices = nativeScanSummary();
        return "uid=" + Process.myUid() + "; " + uinput + "; " + devices;
    }

    @Override
    public synchronized String testRumble() {
        if (activeMode != BridgeMode.NATIVE_DUAL.code()) {
            int tested = 0;
            for (int id : InputDevice.getDeviceIds()) {
                InputDevice device = InputDevice.getDevice(id);
                if (device == null || !isBridgeVirtualDevice(device)) {
                    continue;
                }
                Vibrator vibrator = device.getVibrator();
                if (vibrator != null && vibrator.hasVibrator()) {
                    vibrator.vibrate(VibrationEffect.createOneShot(600L, 180));
                    tested++;
                }
            }
            return tested == 0
                    ? "FF_RUMBLE test failed: Android found no vibrating bridge device"
                    : "FF_RUMBLE test started through Android for " + tested
                            + " virtual controller(s)";
        }
        return JoyConRumbleController.test(serviceContext, nativeScanUniqueIds());
    }

    @Override
    public synchronized String setBridgeMode(int modeCode) {
        BridgeMode mode = BridgeMode.fromCode(modeCode);
        stopCurrentSession();
        reportCount = 0L;
        activeMode = BridgeMode.NATIVE_DUAL.code();

        if (mode == BridgeMode.NATIVE_DUAL) {
            refreshNativeStatus();
            return status;
        }

        String check = probe();
        if (check.startsWith("Error:")) {
            status = check;
            return check;
        }

        try {
            startVirtualDevices(mode);
        } catch (IOException | InterruptedException | RuntimeException error) {
            if (error instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            stopVirtualDevices();
            status = "Error: could not create virtual controller: " + safeMessage(error)
                    + (nativeLastError().isEmpty() ? "" : "; native=" + nativeLastError());
            return status;
        }

        activeMode = mode.code();
        inputRunning = true;
        rumbleRunning = true;
        long generation = sessionGeneration;
        startRumbleBridge(mode, generation);
        inputThread = new Thread(
                () -> runInputLoop(mode, generation),
                "joycon-evdev-input"
        );
        inputThread.setDaemon(true);
        inputThread.start();
        status = mode.label() + " started; FF_RUMBLE ready; scanning Joy-Cons by VID/PID";
        return status;
    }

    @Override
    public String getStatus() {
        if (activeMode == BridgeMode.NATIVE_DUAL.code()) {
            refreshNativeStatus();
        }
        JoyConRumbleController controller = rumbleController;
        String runtimeRumbleError = controller == null ? "" : controller.status();
        return status
                + (deviceDescription.isEmpty() ? "" : "\n" + deviceDescription)
                + (rumbleStatus.isEmpty() ? "" : "\n" + rumbleStatus)
                + (runtimeRumbleError.isEmpty() ? "" : "\n" + runtimeRumbleError);
    }

    @Override
    public int getActiveMode() {
        return activeMode;
    }

    @Override
    public int getDeviceMask() {
        if (activeMode == BridgeMode.NATIVE_DUAL.code()) {
            refreshNativeStatus();
        }
        return deviceMask;
    }

    @Override
    public boolean isGrabbed() {
        return grabbed;
    }

    @Override
    public long getReportCount() {
        return reportCount;
    }

    @Override
    public synchronized void setButtonSwaps(boolean swapAB, boolean swapXY) {
        if (this.swapAB == swapAB && this.swapXY == swapXY) {
            return;
        }
        this.swapAB = swapAB;
        this.swapXY = swapXY;
        mappingRevision++;
    }

    @Override
    public synchronized void setCompatButtonSwaps(
            boolean leftSwapAB,
            boolean leftSwapXY,
            boolean rightSwapAB,
            boolean rightSwapXY
    ) {
        if (compatLeftSwapAB == leftSwapAB
                && compatLeftSwapXY == leftSwapXY
                && compatRightSwapAB == rightSwapAB
                && compatRightSwapXY == rightSwapXY) {
            return;
        }
        compatLeftSwapAB = leftSwapAB;
        compatLeftSwapXY = leftSwapXY;
        compatRightSwapAB = rightSwapAB;
        compatRightSwapXY = rightSwapXY;
        mappingRevision++;
    }

    @Override
    public synchronized void stopBridge() {
        stopCurrentSession();
        activeMode = BridgeMode.NATIVE_DUAL.code();
        refreshNativeStatus();
    }

    @Override
    public synchronized void destroy() {
        stopCurrentSession();
        System.exit(0);
    }

    private void runInputLoop(BridgeMode mode, long generation) {
        JoyConEvdevMapper mapper = new JoyConEvdevMapper();
        long appliedMappingRevision = mappingRevision;
        while (isSessionActive(mode, generation)) {
            long handle = nativeOpenJoyCons(true);
            if (handle == 0L) {
                if (!isSessionActive(mode, generation)) {
                    break;
                }
                deviceMask = 0;
                grabbed = false;
                deviceDescription = "";
                status = mode.label() + "; " + nativeLastError() + "; retrying in 1 second";
                sleepQuietly(1_000L);
                continue;
            }

            if (!isSessionActive(mode, generation)) {
                nativeCloseJoyCons(handle);
                break;
            }

            int openedMask = nativeDeviceMask(handle);
            deviceMask = openedMask;
            grabbed = openedMask != 0;
            deviceDescription = nativeDescribe(handle);
            status = mode.label() + " active; " + sideCountText(openedMask)
                    + "; physical input grabbed with EVIOCGRAB; FF_RUMBLE ready";
            JoyConRumbleController controller = rumbleController;
            if (controller != null) {
                controller.updateDevices(nativeUniqueIds(handle));
            }
            mapper.reset();
            initializeMapper(handle, openedMask, mapper);
            try {
                long targetMappingRevision = mappingRevision;
                sendMappedReports(mode, mapper, 0);
                appliedMappingRevision = targetMappingRevision;
            } catch (IOException error) {
                if (generation == sessionGeneration) {
                    status = "Error: initial HID report failed: " + safeMessage(error);
                    inputRunning = false;
                }
            }

            long partialDeadline = SystemClock.uptimeMillis() + PARTIAL_PAIR_RESCAN_MS;
            int[] event = new int[4];
            int dirtyMask = 0;
            while (isSessionActive(mode, generation)) {
                int result = nativeReadEvent(handle, event, READ_TIMEOUT_MS);
                if (result < 0) {
                    break;
                }
                if (result == 0) {
                    if (appliedMappingRevision != mappingRevision) {
                        try {
                            long targetMappingRevision = mappingRevision;
                            sendMappedReports(mode, mapper, 0);
                            appliedMappingRevision = targetMappingRevision;
                        } catch (IOException error) {
                            if (generation == sessionGeneration) {
                                status = "Error: button-swap update failed: " + safeMessage(error);
                                inputRunning = false;
                            }
                        }
                    }
                    if (openedMask != BOTH_DEVICES
                            && SystemClock.uptimeMillis() >= partialDeadline) {
                        break;
                    }
                    continue;
                }

                int side = event[0];
                int type = event[1];
                int code = event[2];
                int value = event[3];
                if (type == JoyConEvdevMapper.EV_SYN
                        && code == JoyConEvdevMapper.SYN_DROPPED) {
                    break;
                }
                if (type == JoyConEvdevMapper.EV_SYN
                        && code == JoyConEvdevMapper.SYN_REPORT) {
                    boolean mappingChanged = appliedMappingRevision != mappingRevision;
                    if ((dirtyMask & side) != 0 || mappingChanged) {
                        try {
                            long targetMappingRevision = mappingRevision;
                            sendMappedReports(mode, mapper, mappingChanged ? 0 : side);
                            appliedMappingRevision = targetMappingRevision;
                        } catch (IOException error) {
                            if (generation == sessionGeneration) {
                                status = "Error: HID report failed: " + safeMessage(error);
                                inputRunning = false;
                            }
                        }
                        dirtyMask &= ~side;
                    }
                } else {
                    if (mapper.applyEvent(side, type, code, value)) {
                        dirtyMask |= side;
                    }
                }
            }

            nativeCloseJoyCons(handle);
            deviceMask = 0;
            grabbed = false;
            if (isSessionActive(mode, generation)) {
                status = mode.label() + "; Joy-Con topology changed; rescanning";
                sleepQuietly(300L);
            }
        }
        if (generation == sessionGeneration) {
            grabbed = false;
            deviceMask = 0;
        }
    }

    private void initializeMapper(
            long handle,
            int mask,
            JoyConEvdevMapper mapper
    ) {
        if ((mask & JoyConEvdevMapper.SIDE_LEFT) != 0) {
            initializeAxis(handle, JoyConEvdevMapper.SIDE_LEFT, JoyConEvdevMapper.ABS_X, mapper);
            initializeAxis(handle, JoyConEvdevMapper.SIDE_LEFT, JoyConEvdevMapper.ABS_Y, mapper);
            initializeKeys(handle, JoyConEvdevMapper.SIDE_LEFT, mapper);
        }
        if ((mask & JoyConEvdevMapper.SIDE_RIGHT) != 0) {
            initializeAxis(handle, JoyConEvdevMapper.SIDE_RIGHT, JoyConEvdevMapper.ABS_RX, mapper);
            initializeAxis(handle, JoyConEvdevMapper.SIDE_RIGHT, JoyConEvdevMapper.ABS_RY, mapper);
            initializeKeys(handle, JoyConEvdevMapper.SIDE_RIGHT, mapper);
        }
    }

    private void initializeAxis(long handle, int side, int code, JoyConEvdevMapper mapper) {
        int[] info = new int[4];
        if (nativeGetAbsInfo(handle, side, code, info) != 0) {
            mapper.setAxis(side, code, info[0], info[1], info[2], info[3]);
        }
    }

    private void initializeKeys(long handle, int side, JoyConEvdevMapper mapper) {
        for (int key : JoyConEvdevMapper.keysForSide(side)) {
            mapper.setKey(side, key, nativeGetKeyState(handle, side, key) != 0);
        }
    }

    private void sendMappedReports(
            BridgeMode mode,
            JoyConEvdevMapper mapper,
            int changedSide
    ) throws IOException {
        if (mode == BridgeMode.COMBINED) {
            writeVirtualReport(
                    combinedGamepad,
                    HidGamepadReport.encode(mapper.combined(swapAB, swapXY))
            );
            reportCount++;
            return;
        }

        if (changedSide == 0 || changedSide == JoyConEvdevMapper.SIDE_LEFT) {
            writeVirtualReport(
                    leftGamepad,
                    HidGamepadReport.encode(mapper.sidewaysLeft(
                            compatLeftSwapAB,
                            compatLeftSwapXY
                    ))
            );
            reportCount++;
        }
        if (changedSide == 0 || changedSide == JoyConEvdevMapper.SIDE_RIGHT) {
            writeVirtualReport(
                    rightGamepad,
                    HidGamepadReport.encode(mapper.sidewaysRight(
                            compatRightSwapAB,
                            compatRightSwapXY
                    ))
            );
            reportCount++;
        }
    }

    private void startVirtualDevices(BridgeMode mode) throws IOException, InterruptedException {
        if (mode == BridgeMode.COMBINED) {
            combinedGamepad = createVirtualDevice(
                    HidGamepadDescriptor.COMBINED_DEVICE_NAME,
                    0x7001
            );
            writeVirtualReport(combinedGamepad, HidGamepadReport.neutralMapped());
        } else {
            leftGamepad = createVirtualDevice(
                    HidGamepadDescriptor.LEFT_DEVICE_NAME,
                    0x7002
            );
            rightGamepad = createVirtualDevice(
                    HidGamepadDescriptor.RIGHT_DEVICE_NAME,
                    0x7003
            );
            writeVirtualReport(leftGamepad, HidGamepadReport.neutralMapped());
            writeVirtualReport(rightGamepad, HidGamepadReport.neutralMapped());
        }
    }

    private long createVirtualDevice(String name, int productId)
            throws IOException, InterruptedException {
        Set<Integer> before = currentDeviceIds();
        long handle = nativeCreateVirtualGamepad(name, 0x1209, productId);
        if (handle == 0L) {
            throw new IOException(nativeLastError());
        }
        long deadline = SystemClock.uptimeMillis() + DEVICE_REGISTRATION_TIMEOUT_MS;
        while (SystemClock.uptimeMillis() < deadline) {
            for (int deviceId : InputDevice.getDeviceIds()) {
                InputDevice device = InputDevice.getDevice(deviceId);
                if (device != null
                        && name.equals(device.getName())
                        && (!before.contains(deviceId)
                        || device.supportsSource(InputDevice.SOURCE_GAMEPAD))) {
                    return handle;
                }
            }
            Thread.sleep(50L);
        }
        nativeDestroyVirtualGamepad(handle);
        throw new IOException("Android did not register " + name + " within 5 seconds");
    }

    private static void writeVirtualReport(long handle, byte[] report) throws IOException {
        if (handle == 0L || !nativeWriteVirtualGamepad(handle, report)) {
            throw new IOException(nativeLastError());
        }
    }

    private void startRumbleBridge(BridgeMode mode, long generation) {
        try {
            rumbleController = JoyConRumbleController.open(serviceContext);
            rumbleController.updateDevices(nativeScanUniqueIds());
            rumbleStatus = "Automatic rumble: Android FF_RUMBLE -> Nintendo HD Rumble";
        } catch (Throwable error) {
            rumbleController = null;
            rumbleStatus = "Automatic rumble unavailable: " + safeMessage(error);
        }

        if (mode == BridgeMode.COMBINED) {
            combinedRumbleThread = startRumbleThread(
                    combinedGamepad,
                    JoyConRumbleController.SOURCE_COMBINED,
                    mode,
                    generation,
                    "joycon-ff-combined"
            );
        } else {
            leftRumbleThread = startRumbleThread(
                    leftGamepad,
                    JoyConRumbleController.SOURCE_LEFT,
                    mode,
                    generation,
                    "joycon-ff-left"
            );
            rightRumbleThread = startRumbleThread(
                    rightGamepad,
                    JoyConRumbleController.SOURCE_RIGHT,
                    mode,
                    generation,
                    "joycon-ff-right"
            );
        }
    }

    private Thread startRumbleThread(
            long handle,
            int source,
            BridgeMode mode,
            long generation,
            String name
    ) {
        Thread thread = new Thread(
                () -> runRumbleLoop(handle, source, mode, generation),
                name
        );
        thread.setDaemon(true);
        thread.start();
        return thread;
    }

    private void runRumbleLoop(long handle, int source, BridgeMode mode, long generation) {
        int[] effect = new int[4];
        while (rumbleRunning
                && generation == sessionGeneration
                && activeMode == mode.code()) {
            int result = nativeReadRumble(handle, effect, READ_TIMEOUT_MS);
            if (result < 0) {
                if (generation == sessionGeneration) {
                    rumbleStatus = "FF_RUMBLE receiver stopped: " + nativeLastError();
                }
                return;
            }
            if (result == 0) {
                continue;
            }
            JoyConRumbleController controller = rumbleController;
            if (controller != null) {
                controller.play(source, effect[0], effect[1], effect[2], effect[3]);
            }
            if (effect[1] != 0 || effect[2] != 0) {
                rumbleStatus = "FF_RUMBLE received: source=" + source
                        + ", strong=" + effect[1]
                        + ", weak=" + effect[2]
                        + ", durationMs=" + effect[3];
            }
            Log.i(
                    FF_LOG_TAG,
                    "FF_RUMBLE source=" + source
                            + " effect=" + effect[0]
                            + " strong=" + effect[1]
                            + " weak=" + effect[2]
                            + " durationMs=" + effect[3]
            );
        }
    }

    private void stopCurrentSession() {
        inputRunning = false;
        rumbleRunning = false;
        sessionGeneration++;
        Thread thread = inputThread;
        inputThread = null;
        joinThread(thread, 1_600L);
        joinThread(combinedRumbleThread, 500L);
        joinThread(leftRumbleThread, 500L);
        joinThread(rightRumbleThread, 500L);
        combinedRumbleThread = null;
        leftRumbleThread = null;
        rightRumbleThread = null;

        JoyConRumbleController controller = rumbleController;
        rumbleController = null;
        if (controller != null) {
            controller.close();
        }
        grabbed = false;
        deviceMask = 0;
        stopVirtualDevices();
        rumbleStatus = "";
    }

    private void stopVirtualDevices() {
        byte[] neutral = HidGamepadReport.neutralMapped();
        if (combinedGamepad != 0L) {
            nativeWriteVirtualGamepad(combinedGamepad, neutral);
            nativeDestroyVirtualGamepad(combinedGamepad);
            combinedGamepad = 0L;
        }
        if (leftGamepad != 0L) {
            nativeWriteVirtualGamepad(leftGamepad, neutral);
            nativeDestroyVirtualGamepad(leftGamepad);
            leftGamepad = 0L;
        }
        if (rightGamepad != 0L) {
            nativeWriteVirtualGamepad(rightGamepad, neutral);
            nativeDestroyVirtualGamepad(rightGamepad);
            rightGamepad = 0L;
        }
    }

    private static void joinThread(Thread thread, long timeoutMs) {
        if (thread == null || thread == Thread.currentThread()) {
            return;
        }
        try {
            thread.join(timeoutMs);
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
        }
    }

    private void refreshNativeStatus() {
        if (activeMode != BridgeMode.NATIVE_DUAL.code()) {
            return;
        }
        int mask = nativeScanMask();
        String devices = nativeScanSummary();
        deviceMask = mask;
        grabbed = false;
        deviceDescription = mask == 0 ? "" : devices;
        status = "Native dual controllers; " + sideCountText(mask)
                + "; physical input released";
    }

    private static Set<Integer> currentDeviceIds() {
        Set<Integer> result = new HashSet<>();
        for (int id : InputDevice.getDeviceIds()) {
            result.add(id);
        }
        return result;
    }

    private static boolean isBridgeVirtualDevice(InputDevice device) {
        String name = device.getName();
        return HidGamepadDescriptor.COMBINED_DEVICE_NAME.equals(name)
                || HidGamepadDescriptor.LEFT_DEVICE_NAME.equals(name)
                || HidGamepadDescriptor.RIGHT_DEVICE_NAME.equals(name);
    }

    private static String sideCountText(int mask) {
        return switch (mask) {
            case JoyConEvdevMapper.SIDE_LEFT -> "left Joy-Con only";
            case JoyConEvdevMapper.SIDE_RIGHT -> "right Joy-Con only";
            case BOTH_DEVICES -> "both Joy-Cons found";
            default -> "no Joy-Cons found";
        };
    }

    private static void sleepQuietly(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
        }
    }

    private boolean isSessionActive(BridgeMode mode, long generation) {
        return inputRunning
                && generation == sessionGeneration
                && activeMode == mode.code();
    }

    private static String safeMessage(Throwable error) {
        return error.getMessage() == null ? error.getClass().getSimpleName() : error.getMessage();
    }

    private static native long nativeOpenJoyCons(boolean grab);

    private static native void nativeCloseJoyCons(long handle);

    private static native int nativeDeviceMask(long handle);

    private static native String nativeDescribe(long handle);

    private static native String nativeUniqueIds(long handle);

    private static native String nativeLastError();

    private static native String nativeScanSummary();

    private static native int nativeScanMask();

    private static native String nativeScanUniqueIds();

    private static native int nativeReadEvent(long handle, int[] output, int timeoutMs);

    private static native int nativeGetAbsInfo(long handle, int side, int code, int[] output);

    private static native int nativeGetKeyState(long handle, int side, int code);

    private static native String nativeUinputProbe();

    private static native long nativeCreateVirtualGamepad(
            String name,
            int vendorId,
            int productId
    );

    private static native void nativeDestroyVirtualGamepad(long handle);

    private static native boolean nativeWriteVirtualGamepad(long handle, byte[] report);

    private static native int nativeReadRumble(long handle, int[] output, int timeoutMs);
}
