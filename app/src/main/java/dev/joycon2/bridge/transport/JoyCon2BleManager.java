package dev.joycon2.bridge.transport;

import android.Manifest;
import android.annotation.SuppressLint;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothManager;
import android.bluetooth.le.BluetoothLeScanner;
import android.bluetooth.le.ScanCallback;
import android.bluetooth.le.ScanRecord;
import android.bluetooth.le.ScanResult;
import android.bluetooth.le.ScanSettings;
import android.content.Context;
import android.content.pm.PackageManager;
import android.os.Handler;
import android.os.Looper;
import android.os.ParcelUuid;
import android.util.SparseArray;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import dev.joycon2.bridge.protocol.JoyCon2Protocol;
import dev.joycon2.bridge.protocol.JoyCon2Side;

public final class JoyCon2BleManager implements JoyCon2GattSession.Listener {
    public interface Listener {
        void onCandidate(ControllerCandidate candidate);

        void onSessionChanged(ControllerSnapshot snapshot);

        void onScanStateChanged(boolean scanning, String detail);

        void onLog(String message);
    }

    private static final long SCAN_DURATION_MS = 12_000L;

    private final Context context;
    private final Listener listener;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final BluetoothAdapter adapter;
    private final Map<String, ControllerCandidate> candidates = new LinkedHashMap<>();
    private final Map<String, JoyCon2GattSession> sessions = new LinkedHashMap<>();
    private boolean scanning;

    public JoyCon2BleManager(Context context, Listener listener) {
        this.context = context.getApplicationContext();
        this.listener = listener;
        BluetoothManager manager = context.getSystemService(BluetoothManager.class);
        this.adapter = manager == null ? null : manager.getAdapter();
    }

    public boolean isBluetoothSupported() {
        return adapter != null;
    }

    @SuppressLint("MissingPermission")
    public boolean isBluetoothEnabled() {
        if (adapter == null || !hasBluetoothPermissions()) {
            return false;
        }
        try {
            return adapter.isEnabled();
        } catch (SecurityException ignored) {
            return false;
        }
    }

    public boolean hasBluetoothPermissions() {
        return context.checkSelfPermission(Manifest.permission.BLUETOOTH_SCAN)
                == PackageManager.PERMISSION_GRANTED
                && context.checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT)
                == PackageManager.PERMISSION_GRANTED;
    }

    @SuppressLint("MissingPermission")
    public void startScan() {
        if (scanning) {
            return;
        }
        if (!hasBluetoothPermissions()) {
            listener.onScanStateChanged(false, "Bluetooth-Berechtigung fehlt");
            return;
        }
        if (adapter == null) {
            listener.onScanStateChanged(false, "Dieses Gerät unterstützt kein Bluetooth");
            return;
        }
        if (!adapter.isEnabled()) {
            listener.onScanStateChanged(false, "Bluetooth ist ausgeschaltet");
            return;
        }

        BluetoothLeScanner scanner = adapter.getBluetoothLeScanner();
        if (scanner == null) {
            listener.onScanStateChanged(false, "BLE-Scanner ist nicht verfügbar");
            return;
        }

        candidates.clear();
        ScanSettings settings = new ScanSettings.Builder()
                .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
                .setCallbackType(ScanSettings.CALLBACK_TYPE_ALL_MATCHES)
                .build();
        try {
            scanner.startScan(null, settings, scanCallback);
            scanning = true;
            listener.onScanStateChanged(true, "Suche 12 Sekunden nach Joy-Con 2 …");
            listener.onLog("BLE-Scan gestartet");
            mainHandler.postDelayed(this::stopScan, SCAN_DURATION_MS);
        } catch (SecurityException error) {
            listener.onScanStateChanged(false, "Bluetooth-Berechtigung wurde entzogen");
        } catch (RuntimeException error) {
            listener.onScanStateChanged(false, "Scan konnte nicht starten: " + safeMessage(error));
        }
    }

    @SuppressLint("MissingPermission")
    public void stopScan() {
        if (!scanning) {
            return;
        }
        scanning = false;
        if (adapter != null && hasBluetoothPermissions()) {
            BluetoothLeScanner scanner = adapter.getBluetoothLeScanner();
            if (scanner != null) {
                try {
                    scanner.stopScan(scanCallback);
                } catch (RuntimeException ignored) {
                }
            }
        }
        listener.onScanStateChanged(false, candidates.isEmpty()
                ? "Keine Joy-Con 2 gefunden"
                : candidates.size() + " Joy-Con-2-Kandidat(en) gefunden");
        listener.onLog("BLE-Scan beendet");
    }

    public void connect(String address) {
        ControllerCandidate candidate = candidates.get(address);
        if (candidate == null) {
            listener.onLog("Gerät ist nicht mehr in der aktuellen Scanliste");
            return;
        }
        if (sessions.containsKey(address)) {
            listener.onLog(candidate.name() + " wird bereits verbunden");
            return;
        }
        if (sessions.size() >= 2) {
            listener.onLog("Die Bridge unterstützt gleichzeitig maximal zwei Joy-Con 2");
            return;
        }

        stopScan();
        JoyCon2GattSession session = new JoyCon2GattSession(context, candidate, this);
        sessions.put(address, session);
        session.connect();
    }

    public void disconnect(String address) {
        JoyCon2GattSession session = sessions.get(address);
        if (session != null) {
            session.disconnect();
        }
    }

    public void close() {
        stopScan();
        List<JoyCon2GattSession> active = new ArrayList<>(sessions.values());
        sessions.clear();
        for (JoyCon2GattSession session : active) {
            session.disconnect();
        }
    }

    private final ScanCallback scanCallback = new ScanCallback() {
        @Override
        public void onScanResult(int callbackType, ScanResult result) {
            handleScanResult(result);
        }

        @Override
        public void onBatchScanResults(List<ScanResult> results) {
            for (ScanResult result : results) {
                handleScanResult(result);
            }
        }

        @Override
        public void onScanFailed(int errorCode) {
            scanning = false;
            listener.onScanStateChanged(false, "Android-BLE-Scanfehler " + errorCode);
            listener.onLog("BLE-Scan mit Fehlercode " + errorCode + " abgebrochen");
        }
    };

    @SuppressLint("MissingPermission")
    private void handleScanResult(ScanResult result) {
        if (!hasBluetoothPermissions()) {
            return;
        }
        ScanRecord record = result.getScanRecord();
        String name;
        String address;
        try {
            name = result.getDevice().getName();
            if ((name == null || name.isBlank()) && record != null) {
                name = record.getDeviceName();
            }
            address = result.getDevice().getAddress();
        } catch (SecurityException ignored) {
            return;
        }

        boolean serviceMatch = false;
        if (record != null && record.getServiceUuids() != null) {
            for (ParcelUuid service : record.getServiceUuids()) {
                if (JoyCon2Protocol.NINTENDO_SERVICE_UUID.equals(service.getUuid())) {
                    serviceMatch = true;
                    break;
                }
            }
        }

        byte[] manufacturerData = findNintendoManufacturerData(record);
        boolean manufacturerMatch =
                JoyCon2Protocol.looksLikeJoyCon2ManufacturerData(manufacturerData);
        if (!JoyCon2Protocol.isJoyCon2Name(name) && !serviceMatch && !manufacturerMatch) {
            return;
        }

        JoyCon2Side side = JoyCon2Side.fromName(name);
        if (side == JoyCon2Side.UNKNOWN) {
            side = JoyCon2Protocol.sideFromManufacturerData(manufacturerData);
        }
        String displayName = name == null || name.isBlank()
                ? "Joy-Con 2 (BLE-Kandidat)"
                : name;
        ControllerCandidate candidate = new ControllerCandidate(
                result.getDevice(),
                address,
                displayName,
                result.getRssi(),
                side
        );
        candidates.put(address, candidate);
        listener.onCandidate(candidate);
    }

    private static byte[] findNintendoManufacturerData(ScanRecord record) {
        if (record == null) {
            return null;
        }
        byte[] official = record.getManufacturerSpecificData(JoyCon2Protocol.NINTENDO_COMPANY_ID);
        if (JoyCon2Protocol.looksLikeJoyCon2ManufacturerData(official)) {
            return official;
        }
        SparseArray<byte[]> all = record.getManufacturerSpecificData();
        for (int index = 0; index < all.size(); index++) {
            byte[] value = all.valueAt(index);
            if (JoyCon2Protocol.looksLikeJoyCon2ManufacturerData(value)) {
                return value;
            }
        }
        return official;
    }

    @Override
    public void onSessionChanged(ControllerSnapshot snapshot) {
        listener.onSessionChanged(snapshot);
        if (snapshot.stage() == ConnectionStage.ERROR
                || snapshot.stage() == ConnectionStage.DISCONNECTED) {
            sessions.remove(snapshot.address());
        }
    }

    @Override
    public void onLog(String message) {
        listener.onLog(message);
    }

    private static String safeMessage(Throwable error) {
        return error.getMessage() == null ? error.getClass().getSimpleName() : error.getMessage();
    }
}
