package dev.joycon2.bridge.output;

import android.annotation.SuppressLint;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothManager;
import android.bluetooth.BluetoothProfile;
import android.content.Context;
import android.os.SystemClock;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/** Translates Android FF_RUMBLE effects to Nintendo HD-rumble output reports. */
@SuppressLint({"PrivateApi", "MissingPermission"})
final class JoyConRumbleController implements AutoCloseable {
    static final int SOURCE_COMBINED = 0;
    static final int SOURCE_LEFT = 1;
    static final int SOURCE_RIGHT = 2;

    private static final int HID_HOST = 4;
    private static final int PACKET_INTERVAL_MS = 50;
    private static final int STOP_PACKETS = 5;
    private static final int MAX_NINTENDO_AMPLITUDE = 1003;
    private static final String NEUTRAL_RUMBLE_MOTOR = "00014040";

    // Nintendo's amplitude lookup table, also used by the upstream hid-nintendo driver.
    private static final int[] RUMBLE_AMPLITUDES = {
            0, 10, 12, 14, 17, 20, 24, 28, 33, 40, 47, 56, 67, 80, 95, 112,
            117, 123, 128, 134, 140, 146, 152, 159, 166, 173, 181, 189, 198, 206,
            215, 225, 230, 235, 240, 245, 251, 256, 262, 268, 273, 279, 286, 292,
            298, 305, 311, 318, 325, 332, 340, 347, 355, 362, 370, 378, 387, 395,
            404, 413, 422, 431, 440, 450, 460, 470, 480, 491, 501, 512, 524, 535,
            547, 559, 571, 584, 596, 609, 623, 636, 650, 665, 679, 694, 709, 725,
            741, 757, 773, 790, 808, 825, 843, 862, 881, 900, 920, 940, 960, 981,
            1003
    };

    private final Object lock = new Object();
    private final HidConnection connection;
    private final ScheduledExecutorService worker;
    private final Map<Long, ActiveEffect> effects = new HashMap<>();

    private BluetoothDevice leftDevice;
    private BluetoothDevice rightDevice;
    private boolean leftRumbleEnabled;
    private boolean rightRumbleEnabled;
    private int lastLeftMagnitude;
    private int lastRightMagnitude;
    private int leftStopPackets;
    private int rightStopPackets;
    private int packetNumber;
    private boolean closed;
    private String lastError = "";

    private JoyConRumbleController(HidConnection connection) {
        this.connection = connection;
        worker = Executors.newSingleThreadScheduledExecutor(runnable -> {
            Thread thread = new Thread(runnable, "joycon-ff-rumble");
            thread.setDaemon(true);
            return thread;
        });
        worker.scheduleAtFixedRate(
                this::tickSafely,
                0,
                PACKET_INTERVAL_MS,
                TimeUnit.MILLISECONDS
        );
    }

    static JoyConRumbleController open(Context context) throws Exception {
        return new JoyConRumbleController(HidConnection.open(context));
    }

    static String test(Context context, String uniqueIds) {
        if (context == null) {
            return "Vibration test failed: Shizuku service context is unavailable";
        }
        try (HidConnection connection = HidConnection.open(context)) {
            List<BluetoothDevice> devices = devicesFromUniqueIds(connection.adapter, uniqueIds);
            if (devices.isEmpty()) {
                return "Vibration test failed: no connected Joy-Con Bluetooth addresses found";
            }
            int completed = 0;
            for (BluetoothDevice device : devices) {
                runTestPulse(connection.sender, device);
                completed++;
                Thread.sleep(250L);
            }
            return "Vibration test completed for " + completed + " Joy-Con(s)";
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
            return "Vibration test interrupted";
        } catch (Throwable error) {
            return "Vibration test failed: " + safeMessage(unwrap(error));
        }
    }

    void updateDevices(String uniqueIds) {
        String leftAddress = null;
        String rightAddress = null;
        if (uniqueIds != null) {
            for (String entry : uniqueIds.split(";")) {
                int separator = entry.indexOf('=');
                if (separator <= 0) {
                    continue;
                }
                String side = entry.substring(0, separator).trim();
                String address = entry.substring(separator + 1).trim();
                if (!BluetoothAdapter.checkBluetoothAddress(address)) {
                    continue;
                }
                if (side.equals("L")) {
                    leftAddress = address;
                } else if (side.equals("R")) {
                    rightAddress = address;
                }
            }
        }

        synchronized (lock) {
            BluetoothDevice newLeft = remoteDevice(leftAddress);
            BluetoothDevice newRight = remoteDevice(rightAddress);
            if (!sameDevice(leftDevice, newLeft)) {
                leftDevice = newLeft;
                leftRumbleEnabled = false;
                leftStopPackets = 0;
            }
            if (!sameDevice(rightDevice, newRight)) {
                rightDevice = newRight;
                rightRumbleEnabled = false;
                rightStopPackets = 0;
            }
        }
    }

    void play(int source, int effectId, int strongMagnitude, int weakMagnitude, int durationMs) {
        long key = (((long) source) << 32) | (effectId & 0xffffffffL);
        synchronized (lock) {
            if (closed) {
                return;
            }
            int strong = clampMagnitude(strongMagnitude);
            int weak = clampMagnitude(weakMagnitude);
            if (strong == 0 && weak == 0) {
                effects.remove(key);
                return;
            }
            long deadline = durationMs <= 0
                    ? Long.MAX_VALUE
                    : SystemClock.uptimeMillis() + durationMs;
            effects.put(key, new ActiveEffect(source, strong, weak, deadline));
        }
    }

    String status() {
        synchronized (lock) {
            return lastError;
        }
    }

    @Override
    public void close() {
        synchronized (lock) {
            if (closed) {
                return;
            }
            closed = true;
            effects.clear();
        }
        worker.shutdownNow();
        try {
            worker.awaitTermination(300L, TimeUnit.MILLISECONDS);
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
        }
        sendFinalNeutral(leftDevice, leftRumbleEnabled);
        sendFinalNeutral(rightDevice, rightRumbleEnabled);
        connection.close();
    }

    private void tickSafely() {
        try {
            tick();
        } catch (Throwable error) {
            synchronized (lock) {
                lastError = "FF_RUMBLE output failed: " + safeMessage(unwrap(error));
            }
        }
    }

    private void tick() throws Exception {
        synchronized (lock) {
            if (closed) {
                return;
            }
            long now = SystemClock.uptimeMillis();
            effects.entrySet().removeIf(entry -> entry.getValue().deadline <= now);

            int leftMagnitude = 0;
            int rightMagnitude = 0;
            for (ActiveEffect effect : effects.values()) {
                if (effect.source == SOURCE_COMBINED) {
                    int left = effect.strong != 0 ? effect.strong : effect.weak;
                    int right = effect.weak != 0 ? effect.weak : effect.strong;
                    leftMagnitude = Math.max(leftMagnitude, left);
                    rightMagnitude = Math.max(rightMagnitude, right);
                } else if (effect.source == SOURCE_LEFT) {
                    leftMagnitude = Math.max(leftMagnitude, Math.max(effect.strong, effect.weak));
                } else if (effect.source == SOURCE_RIGHT) {
                    rightMagnitude = Math.max(rightMagnitude, Math.max(effect.strong, effect.weak));
                }
            }

            if (lastLeftMagnitude != 0 && leftMagnitude == 0) {
                leftStopPackets = STOP_PACKETS;
            }
            if (lastRightMagnitude != 0 && rightMagnitude == 0) {
                rightStopPackets = STOP_PACKETS;
            }
            lastLeftMagnitude = leftMagnitude;
            lastRightMagnitude = rightMagnitude;

            if (leftMagnitude != 0 || leftStopPackets > 0) {
                leftRumbleEnabled = sendTick(leftDevice, leftRumbleEnabled, leftMagnitude);
                if (leftMagnitude == 0 && leftStopPackets > 0) {
                    leftStopPackets--;
                }
            }
            if (rightMagnitude != 0 || rightStopPackets > 0) {
                rightRumbleEnabled = sendTick(rightDevice, rightRumbleEnabled, rightMagnitude);
                if (rightMagnitude == 0 && rightStopPackets > 0) {
                    rightStopPackets--;
                }
            }
            lastError = "";
        }
    }

    private boolean sendTick(BluetoothDevice device, boolean enabled, int magnitude)
            throws Exception {
        if (device == null) {
            return false;
        }
        if (!enabled) {
            connection.sender.send(device, enableRumbleReport(packetNumber++));
            return true;
        }
        connection.sender.send(device, rumbleReport(packetNumber++, motorData(magnitude)));
        return true;
    }

    private void sendFinalNeutral(BluetoothDevice device, boolean enabled) {
        if (device == null || !enabled) {
            return;
        }
        for (int index = 0; index < STOP_PACKETS; index++) {
            try {
                connection.sender.send(device, rumbleReport(packetNumber++, NEUTRAL_RUMBLE_MOTOR));
                Thread.sleep(10L);
            } catch (Throwable error) {
                if (error instanceof InterruptedException) {
                    Thread.currentThread().interrupt();
                }
                return;
            }
        }
    }

    private BluetoothDevice remoteDevice(String address) {
        return address == null ? null : connection.adapter.getRemoteDevice(address);
    }

    private static boolean sameDevice(BluetoothDevice first, BluetoothDevice second) {
        return first == null ? second == null : first.equals(second);
    }

    private static int clampMagnitude(int magnitude) {
        return Math.max(0, Math.min(0xffff, magnitude));
    }

    static String motorData(int magnitude) {
        int target = Math.round(clampMagnitude(magnitude)
                * (MAX_NINTENDO_AMPLITUDE / 65535f));
        int index = 0;
        while (index < RUMBLE_AMPLITUDES.length - 1
                && target > RUMBLE_AMPLITUDES[index]) {
            index++;
        }
        int highAmplitude = index * 2;
        int lowAmplitude = 0x40 + index / 2;
        if ((index & 1) != 0) {
            lowAmplitude |= 0x8000;
        }
        return byteHex(0x00)
                + byteHex(0x01 + highAmplitude)
                + byteHex(0x40 + (lowAmplitude >> 8))
                + byteHex(lowAmplitude);
    }

    private static void runTestPulse(DirectHidSender sender, BluetoothDevice device)
            throws Exception {
        int packet = 0;
        sender.send(device, enableRumbleReport(packet++));
        Thread.sleep(PACKET_INTERVAL_MS);
        try {
            for (int index = 0; index < 6; index++) {
                sender.send(device, rumbleReport(packet++, motorData(15_000)));
                Thread.sleep(PACKET_INTERVAL_MS);
            }
        } finally {
            for (int index = 0; index < STOP_PACKETS; index++) {
                sender.send(device, rumbleReport(packet++, NEUTRAL_RUMBLE_MOTOR));
                Thread.sleep(PACKET_INTERVAL_MS);
            }
        }
    }

    private static String enableRumbleReport(int packet) {
        return "01" + byteHex(packet) + NEUTRAL_RUMBLE_MOTOR
                + NEUTRAL_RUMBLE_MOTOR + "4801";
    }

    private static String rumbleReport(int packet, String motorData) {
        return "10" + byteHex(packet & 0x0f) + motorData + motorData;
    }

    private static String byteHex(int value) {
        return String.format(Locale.US, "%02X", value & 0xff);
    }

    private static List<BluetoothDevice> devicesFromUniqueIds(
            BluetoothAdapter adapter,
            String uniqueIds) {
        List<BluetoothDevice> result = new ArrayList<>(2);
        if (uniqueIds == null || uniqueIds.isBlank()) {
            return result;
        }
        for (String entry : uniqueIds.split(";")) {
            int separator = entry.indexOf('=');
            String address = separator < 0 ? entry.trim() : entry.substring(separator + 1).trim();
            if (!BluetoothAdapter.checkBluetoothAddress(address)) {
                continue;
            }
            BluetoothDevice device = adapter.getRemoteDevice(address);
            if (!result.contains(device)) {
                result.add(device);
            }
        }
        return result;
    }

    private record ActiveEffect(int source, int strong, int weak, long deadline) {
    }

    private static final class HidConnection implements AutoCloseable {
        private final BluetoothAdapter adapter;
        private final BluetoothProfile hidHost;
        private final DirectHidSender sender;

        private HidConnection(
                BluetoothAdapter adapter,
                BluetoothProfile hidHost,
                DirectHidSender sender) {
            this.adapter = adapter;
            this.hidHost = hidHost;
            this.sender = sender;
        }

        static HidConnection open(Context context) throws Exception {
            Context shellContext = shellContext(context);
            BluetoothAdapter adapter = createAdapter(shellContext);
            if (adapter == null) {
                throw new IllegalStateException("Bluetooth adapter is unavailable");
            }

            CountDownLatch connected = new CountDownLatch(1);
            AtomicReference<BluetoothProfile> reference = new AtomicReference<>();
            boolean requested = adapter.getProfileProxy(
                    shellContext,
                    new BluetoothProfile.ServiceListener() {
                        @Override
                        public void onServiceConnected(int profile, BluetoothProfile proxy) {
                            reference.set(proxy);
                            connected.countDown();
                        }

                        @Override
                        public void onServiceDisconnected(int profile) {
                            // A later bridge session requests a fresh proxy.
                        }
                    },
                    HID_HOST
            );
            if (!requested || !connected.await(3, TimeUnit.SECONDS)) {
                throw new IllegalStateException("Bluetooth HID Host service did not connect");
            }
            BluetoothProfile hidHost = reference.get();
            if (hidHost == null) {
                throw new IllegalStateException("Bluetooth HID Host proxy is unavailable");
            }
            try {
                return new HidConnection(adapter, hidHost, DirectHidSender.from(hidHost));
            } catch (Throwable error) {
                adapter.closeProfileProxy(HID_HOST, hidHost);
                throw error;
            }
        }

        @Override
        public void close() {
            try {
                adapter.closeProfileProxy(HID_HOST, hidHost);
            } catch (RuntimeException ignored) {
            }
        }
    }

    private static Context shellContext(Context context) {
        try {
            return context.createPackageContext("com.android.shell", Context.CONTEXT_IGNORE_SECURITY);
        } catch (android.content.pm.PackageManager.NameNotFoundException | RuntimeException ignored) {
            return context;
        }
    }

    private static BluetoothAdapter createAdapter(Context context) throws Exception {
        BluetoothManager manager = (BluetoothManager) context.getSystemService(Context.BLUETOOTH_SERVICE);
        BluetoothAdapter adapter = manager == null ? null : manager.getAdapter();
        if (adapter != null) {
            return adapter;
        }

        Class<?> serviceManager = Class.forName("android.os.ServiceManager");
        Object managerBinder = serviceManager.getDeclaredMethod("getService", String.class)
                .invoke(null, "bluetooth_manager");
        if (managerBinder == null) {
            return null;
        }
        Class<?> binderClass = Class.forName("android.os.IBinder");
        Class<?> managerInterface = Class.forName("android.bluetooth.IBluetoothManager");
        Class<?> managerStub = Class.forName("android.bluetooth.IBluetoothManager$Stub");
        Object managerProxy = managerStub.getDeclaredMethod("asInterface", binderClass)
                .invoke(null, managerBinder);
        Object attribution = Context.class.getDeclaredMethod("getAttributionSource")
                .invoke(context);

        for (Constructor<?> constructor : BluetoothAdapter.class.getDeclaredConstructors()) {
            Class<?>[] parameters = constructor.getParameterTypes();
            if (parameters.length == 2
                    && parameters[0].isAssignableFrom(managerInterface)
                    && parameters[1].getName().equals("android.content.AttributionSource")) {
                constructor.setAccessible(true);
                return (BluetoothAdapter) constructor.newInstance(managerProxy, attribution);
            }
        }
        return null;
    }

    private static Throwable unwrap(Throwable error) {
        if (error instanceof InvocationTargetException invocation && invocation.getCause() != null) {
            return invocation.getCause();
        }
        return error;
    }

    private static String safeMessage(Throwable error) {
        return error.getMessage() == null ? error.getClass().getSimpleName() : error.getMessage();
    }

    private static final class DirectHidSender {
        private final Object service;
        private final Object attributionSource;
        private final Method sendData;

        private DirectHidSender(Object service, Object attributionSource, Method sendData) {
            this.service = service;
            this.attributionSource = attributionSource;
            this.sendData = sendData;
        }

        static DirectHidSender from(BluetoothProfile hidHost) throws Exception {
            Field serviceField = hidHost.getClass().getDeclaredField("mService");
            serviceField.setAccessible(true);
            Object service = serviceField.get(hidHost);
            if (service == null) {
                throw new IllegalStateException("Bluetooth HID Host Binder is unavailable");
            }

            Field attributionField = hidHost.getClass().getDeclaredField("mAttributionSource");
            attributionField.setAccessible(true);
            Object attributionSource = attributionField.get(hidHost);
            for (Method method : service.getClass().getMethods()) {
                if (method.getName().equals("sendData")
                        && method.getParameterTypes().length == 3) {
                    method.setAccessible(true);
                    return new DirectHidSender(service, attributionSource, method);
                }
            }
            throw new NoSuchMethodException("IBluetoothHidHost.sendData");
        }

        void send(BluetoothDevice device, String report) throws Exception {
            Object result = sendData.invoke(service, device, report, attributionSource);
            if (!(result instanceof Boolean accepted) || !accepted) {
                throw new IllegalStateException("Bluetooth HID Host rejected a rumble report");
            }
        }
    }
}
