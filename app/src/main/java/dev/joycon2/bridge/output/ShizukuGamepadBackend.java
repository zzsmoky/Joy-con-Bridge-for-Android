package dev.joycon2.bridge.output;

import static android.content.pm.PackageManager.PERMISSION_GRANTED;

import android.content.ComponentName;
import android.content.Context;
import android.content.ServiceConnection;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.IBinder;
import android.os.RemoteException;
import android.view.InputDevice;

import dev.joycon2.bridge.BuildConfig;
import rikka.shizuku.Shizuku;

/** App-process controller for the Shizuku shell UserService. */
public final class ShizukuGamepadBackend {
    public interface Listener {
        void onOutputChanged(OutputSnapshot snapshot);

        void onOutputLog(String message);
    }

    private static final int REQUEST_SHIZUKU_PERMISSION = 7001;
    private static final long STATUS_POLL_MS = 750L;

    private final Handler mainHandler;
    private final HandlerThread workerThread;
    private final Handler worker;
    private final Listener listener;
    private final Shizuku.UserServiceArgs userServiceArgs;
    private final Shizuku.OnBinderReceivedListener binderReceivedListener;
    private final Shizuku.OnBinderDeadListener binderDeadListener;
    private final Shizuku.OnRequestPermissionResultListener permissionResultListener;

    private volatile IInputInjectionService remote;
    private volatile BridgeMode desiredMode = BridgeMode.NATIVE_DUAL;
    private volatile boolean desiredSwapAB;
    private volatile boolean desiredSwapXY;
    private volatile boolean desiredCompatLeftSwapAB;
    private volatile boolean desiredCompatLeftSwapXY;
    private volatile boolean desiredCompatRightSwapAB;
    private volatile boolean desiredCompatRightSwapXY;
    private OutputSnapshot snapshot = OutputSnapshot.initial();
    private boolean started;
    private boolean binding;

    public ShizukuGamepadBackend(Context context, Handler mainHandler, Listener listener) {
        this.mainHandler = mainHandler;
        this.listener = listener;
        workerThread = new HandlerThread("joycon-shizuku-control");
        workerThread.start();
        worker = new Handler(workerThread.getLooper());
        userServiceArgs = new Shizuku.UserServiceArgs(new ComponentName(
                BuildConfig.APPLICATION_ID,
                ShizukuInputService.class.getName()
        ))
                .daemon(false)
                .processNameSuffix("evdev")
                .debuggable(BuildConfig.DEBUG)
                .version(BuildConfig.VERSION_CODE);

        binderReceivedListener = () -> {
            listener.onOutputLog("Shizuku service connected");
            refreshAvailability();
        };
        binderDeadListener = () -> {
            binding = false;
            remote = null;
            transition(
                    OutputStage.SHIZUKU_STOPPED,
                    desiredMode,
                    "Shizuku stopped; physical Joy-Cons are available to Android",
                    0L,
                    -1,
                    0,
                    false
            );
        };
        permissionResultListener = (requestCode, grantResult) -> {
            if (requestCode != REQUEST_SHIZUKU_PERMISSION) {
                return;
            }
            if (grantResult == PERMISSION_GRANTED) {
                listener.onOutputLog("Shizuku permission granted");
                bindUserService();
            } else {
                transition(
                        OutputStage.PERMISSION_REQUIRED,
                        desiredMode,
                        "Shizuku permission denied",
                        0L,
                        currentUid(),
                        0,
                        false
                );
            }
        };
    }

    public void start(
            BridgeMode initialMode,
            boolean swapAB,
            boolean swapXY,
            boolean compatLeftSwapAB,
            boolean compatLeftSwapXY,
            boolean compatRightSwapAB,
            boolean compatRightSwapXY
    ) {
        if (started) {
            return;
        }
        started = true;
        desiredMode = initialMode == null ? BridgeMode.NATIVE_DUAL : initialMode;
        desiredSwapAB = swapAB;
        desiredSwapXY = swapXY;
        desiredCompatLeftSwapAB = compatLeftSwapAB;
        desiredCompatLeftSwapXY = compatLeftSwapXY;
        desiredCompatRightSwapAB = compatRightSwapAB;
        desiredCompatRightSwapXY = compatRightSwapXY;
        Shizuku.addBinderReceivedListenerSticky(binderReceivedListener);
        Shizuku.addBinderDeadListener(binderDeadListener);
        Shizuku.addRequestPermissionResultListener(permissionResultListener);
        refreshAvailability();
    }

    public void selectMode(BridgeMode mode) {
        desiredMode = mode == null ? BridgeMode.NATIVE_DUAL : mode;
        transition(
                OutputStage.BINDING,
                desiredMode,
                "Switching to " + desiredMode.label(),
                snapshot.injectedEvents(),
                currentUid(),
                snapshot.deviceMask(),
                snapshot.grabbed()
        );
        IInputInjectionService service = remote;
        if (service != null) {
            applyDesiredMode(service);
        } else {
            ensurePermissionAndService(desiredMode != BridgeMode.NATIVE_DUAL);
        }
    }

    public void setButtonSwaps(boolean swapAB, boolean swapXY) {
        desiredSwapAB = swapAB;
        desiredSwapXY = swapXY;
        IInputInjectionService service = remote;
        if (service == null) {
            return;
        }
        worker.post(() -> {
            if (remote != service) {
                return;
            }
            try {
                service.setButtonSwaps(desiredSwapAB, desiredSwapXY);
            } catch (RemoteException | RuntimeException error) {
                listener.onOutputLog("Button-swap update failed: " + safeMessage(error));
            }
        });
    }

    public void setCompatButtonSwaps(
            boolean leftSwapAB,
            boolean leftSwapXY,
            boolean rightSwapAB,
            boolean rightSwapXY
    ) {
        desiredCompatLeftSwapAB = leftSwapAB;
        desiredCompatLeftSwapXY = leftSwapXY;
        desiredCompatRightSwapAB = rightSwapAB;
        desiredCompatRightSwapXY = rightSwapXY;
        IInputInjectionService service = remote;
        if (service == null) {
            return;
        }
        worker.post(() -> {
            if (remote != service) {
                return;
            }
            try {
                service.setCompatButtonSwaps(
                        desiredCompatLeftSwapAB,
                        desiredCompatLeftSwapXY,
                        desiredCompatRightSwapAB,
                        desiredCompatRightSwapXY
                );
            } catch (RemoteException | RuntimeException error) {
                listener.onOutputLog("Compatible-mode button-swap update failed: "
                        + safeMessage(error));
            }
        });
    }

    public void retry() {
        ensurePermissionAndService(true);
        IInputInjectionService service = remote;
        if (service != null) {
            applyDesiredMode(service);
        }
    }

    public boolean testRumble() {
        IInputInjectionService service = remote;
        if (service == null) {
            listener.onOutputLog("Vibration test unavailable: Shizuku service is not connected");
            ensurePermissionAndService(true);
            return false;
        }
        worker.post(() -> {
            if (remote != service) {
                return;
            }
            try {
                String result = service.testRumble();
                listener.onOutputLog(result == null
                        ? "Vibration test failed: no response from Shizuku service"
                        : result);
            } catch (RemoteException | RuntimeException error) {
                listener.onOutputLog("Vibration test failed: " + safeMessage(error));
            }
        });
        return true;
    }

    public OutputSnapshot snapshot() {
        return snapshot;
    }

    public void stop() {
        if (!started) {
            return;
        }
        started = false;
        worker.removeCallbacksAndMessages(null);
        IInputInjectionService service = remote;
        if (service != null) {
            try {
                service.stopBridge();
            } catch (RemoteException ignored) {
            }
        }
        remote = null;
        try {
            if (Shizuku.pingBinder()) {
                Shizuku.unbindUserService(userServiceArgs, serviceConnection, true);
            }
        } catch (RuntimeException ignored) {
        }
        Shizuku.removeBinderReceivedListener(binderReceivedListener);
        Shizuku.removeBinderDeadListener(binderDeadListener);
        Shizuku.removeRequestPermissionResultListener(permissionResultListener);
        workerThread.quitSafely();
    }

    private final ServiceConnection serviceConnection = new ServiceConnection() {
        @Override
        public void onServiceConnected(ComponentName name, IBinder binder) {
            binding = false;
            if (binder == null || !binder.pingBinder()) {
                fail("Shizuku UserService returned an invalid Binder");
                return;
            }
            IInputInjectionService service = IInputInjectionService.Stub.asInterface(binder);
            remote = service;
            worker.post(() -> prepareConnectedService(service));
        }

        @Override
        public void onServiceDisconnected(ComponentName name) {
            binding = false;
            remote = null;
            fail("Shizuku UserService disconnected");
        }
    };

    private void prepareConnectedService(IInputInjectionService service) {
        try {
            String probe = service.probe();
            if (probe == null || probe.startsWith("Error:")) {
                fail(probe == null ? "Shizuku probe returned no response" : probe);
                return;
            }
            listener.onOutputLog("Shell input bridge available: " + probe);
            applyDesiredModeNow(service);
            schedulePoll();
        } catch (RemoteException | RuntimeException error) {
            fail("Shizuku UserService probe failed: " + safeMessage(error));
        }
    }

    private void applyDesiredMode(IInputInjectionService service) {
        worker.post(() -> applyDesiredModeNow(service));
    }

    private void applyDesiredModeNow(IInputInjectionService service) {
        if (remote != service) {
            return;
        }
        BridgeMode requested = desiredMode;
        try {
            service.setButtonSwaps(desiredSwapAB, desiredSwapXY);
            service.setCompatButtonSwaps(
                    desiredCompatLeftSwapAB,
                    desiredCompatLeftSwapXY,
                    desiredCompatRightSwapAB,
                    desiredCompatRightSwapXY
            );
            String result = service.setBridgeMode(requested.code());
            if (result == null || result.startsWith("Error:")) {
                fail(result == null ? "Mode switch returned no response" : result);
                return;
            }
            listener.onOutputLog("Switched to " + requested.label() + ": " + result);
            readRemoteSnapshot(service);
        } catch (RemoteException | RuntimeException error) {
            fail("Mode switch failed: " + safeMessage(error));
        }
    }

    private void schedulePoll() {
        worker.removeCallbacks(statusPoll);
        worker.postDelayed(statusPoll, STATUS_POLL_MS);
    }

    private final Runnable statusPoll = new Runnable() {
        @Override
        public void run() {
            IInputInjectionService service = remote;
            if (!started || service == null) {
                return;
            }
            readRemoteSnapshot(service);
            worker.postDelayed(this, STATUS_POLL_MS);
        }
    };

    private void readRemoteSnapshot(IInputInjectionService service) {
        try {
            String detail = service.getStatus();
            BridgeMode active = BridgeMode.fromCode(service.getActiveMode());
            int mask = service.getDeviceMask();
            boolean grabbed = service.isGrabbed();
            long reports = service.getReportCount();
            OutputStage stage;
            if (detail == null || detail.startsWith("Error:")) {
                stage = OutputStage.ERROR;
            } else if (active == BridgeMode.NATIVE_DUAL) {
                stage = OutputStage.READY;
            } else {
                stage = OutputStage.ACTIVE;
            }
            transition(
                    stage,
                    active,
                    detail == null ? "UserService status returned no response" : detail,
                    reports,
                    currentUid(),
                    mask,
                    grabbed
            );
        } catch (RemoteException | RuntimeException error) {
            fail("Could not read bridge status: " + safeMessage(error));
        }
    }

    private void refreshAvailability() {
        if (!started) {
            return;
        }
        if (!Shizuku.pingBinder()) {
            if (desiredMode == BridgeMode.NATIVE_DUAL) {
                showNativeWithoutUserService("Shizuku is not running");
            } else {
                transition(
                        OutputStage.SHIZUKU_STOPPED,
                        desiredMode,
                        "Start Shizuku first",
                        0L,
                        -1,
                        0,
                        false
                );
            }
            return;
        }
        ensurePermissionAndService(false);
    }

    private void ensurePermissionAndService(boolean requestPermission) {
        if (!Shizuku.pingBinder()) {
            if (desiredMode == BridgeMode.NATIVE_DUAL) {
                showNativeWithoutUserService("Shizuku is not running");
            } else {
                transition(
                        OutputStage.SHIZUKU_STOPPED,
                        desiredMode,
                        "Start Shizuku, then select the mode again",
                        0L,
                        -1,
                        0,
                        false
                );
            }
            return;
        }
        try {
            if (Shizuku.isPreV11() || Shizuku.getVersion() < 11) {
                fail("Shizuku 11 or newer is required");
                return;
            }
            if (Shizuku.checkSelfPermission() != PERMISSION_GRANTED) {
                if (desiredMode == BridgeMode.NATIVE_DUAL) {
                    showNativeWithoutUserService("Shizuku permission has not been granted");
                    return;
                }
                transition(
                        OutputStage.PERMISSION_REQUIRED,
                        desiredMode,
                        "Shizuku shell permission required",
                        0L,
                        currentUid(),
                        0,
                        false
                );
                if (requestPermission && !Shizuku.shouldShowRequestPermissionRationale()) {
                    Shizuku.requestPermission(REQUEST_SHIZUKU_PERMISSION);
                }
                return;
            }
            bindUserService();
        } catch (RuntimeException error) {
            fail("Shizuku access failed: " + safeMessage(error));
        }
    }

    private void bindUserService() {
        IInputInjectionService service = remote;
        if (service != null) {
            return;
        }
        if (binding) {
            return;
        }
        binding = true;
        transition(
                OutputStage.BINDING,
                desiredMode,
                "Starting shell-privileged background service",
                0L,
                currentUid(),
                0,
                false
        );
        try {
            Shizuku.bindUserService(userServiceArgs, serviceConnection);
        } catch (RuntimeException error) {
            binding = false;
            fail("UserService startup failed: " + safeMessage(error));
        }
    }

    private void fail(String detail) {
        transition(
                OutputStage.ERROR,
                desiredMode,
                detail,
                snapshot.injectedEvents(),
                currentUid(),
                0,
                false
        );
        listener.onOutputLog(detail);
    }

    private void showNativeWithoutUserService(String reason) {
        transition(
                OutputStage.READY,
                BridgeMode.NATIVE_DUAL,
                "Native dual controllers do not require Shizuku; Android reads physical input ("
                        + reason + ")",
                0L,
                currentUid(),
                androidJoyConMask(),
                false
        );
    }

    private static int androidJoyConMask() {
        int mask = 0;
        for (int id : InputDevice.getDeviceIds()) {
            InputDevice device = InputDevice.getDevice(id);
            if (device == null || device.getVendorId() != 0x057e
                    || device.getName().contains("IMU")) {
                continue;
            }
            if (device.getProductId() == 0x2006) {
                mask |= JoyConEvdevMapper.SIDE_LEFT;
            } else if (device.getProductId() == 0x2007) {
                mask |= JoyConEvdevMapper.SIDE_RIGHT;
            }
        }
        return mask;
    }

    private void transition(
            OutputStage stage,
            BridgeMode mode,
            String detail,
            long reports,
            int uid,
            int mask,
            boolean grabbed
    ) {
        if (android.os.Looper.myLooper() != android.os.Looper.getMainLooper()) {
            mainHandler.post(() -> transition(stage, mode, detail, reports, uid, mask, grabbed));
            return;
        }
        snapshot = new OutputSnapshot(stage, mode, detail, reports, uid, mask, grabbed);
        listener.onOutputChanged(snapshot);
    }

    private static int currentUid() {
        try {
            return Shizuku.pingBinder() ? Shizuku.getUid() : -1;
        } catch (RuntimeException ignored) {
            return -1;
        }
    }

    private static String safeMessage(Throwable error) {
        return error.getMessage() == null ? error.getClass().getSimpleName() : error.getMessage();
    }
}
