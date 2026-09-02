package dev.joycon2.bridge.transport;

import android.annotation.SuppressLint;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothGatt;
import android.bluetooth.BluetoothGattCallback;
import android.bluetooth.BluetoothGattCharacteristic;
import android.bluetooth.BluetoothGattDescriptor;
import android.bluetooth.BluetoothGattService;
import android.bluetooth.BluetoothProfile;
import android.bluetooth.BluetoothStatusCodes;
import android.content.Context;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;

import java.util.Arrays;
import java.util.Optional;

import dev.joycon2.bridge.protocol.JoyCon2InputState;
import dev.joycon2.bridge.protocol.JoyCon2Protocol;
import dev.joycon2.bridge.protocol.JoyCon2ReportDecoder;
import dev.joycon2.bridge.protocol.JoyCon2Side;

final class JoyCon2GattSession {
    interface Listener {
        void onSessionChanged(ControllerSnapshot snapshot);

        void onLog(String message);
    }

    private final Context context;
    private final ControllerCandidate candidate;
    private final Listener listener;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final JoyCon2ReportDecoder decoder = new JoyCon2ReportDecoder();

    private BluetoothGatt gatt;
    private BluetoothGattCharacteristic inputCharacteristic;
    private BluetoothGattCharacteristic outputCharacteristic;
    private BluetoothGattCharacteristic pnpCharacteristic;
    private JoyCon2Side side;
    private ConnectionStage stage = ConnectionStage.CONNECTING;
    private String detail = "Verbindung wird aufgebaut";
    private int rssi;
    private long frameCount;
    private long lastFrameNanos;
    private double reportRateHz;
    private JoyCon2InputState inputState;
    private String lastReportHex = "—";
    private boolean discoveryRequested;
    private boolean closedByUser;

    JoyCon2GattSession(Context context, ControllerCandidate candidate, Listener listener) {
        this.context = context.getApplicationContext();
        this.candidate = candidate;
        this.listener = listener;
        this.side = candidate.side();
        this.rssi = candidate.rssi();
    }

    String address() {
        return candidate.address();
    }

    @SuppressLint("MissingPermission")
    void connect() {
        log("Verbinde " + candidate.name() + " direkt über BLE/GATT");
        emit();
        try {
            gatt = candidate.device().connectGatt(
                    context,
                    false,
                    callback,
                    BluetoothDevice.TRANSPORT_LE,
                    BluetoothDevice.PHY_LE_1M_MASK
            );
            if (gatt == null) {
                fail("Android konnte keinen GATT-Client anlegen");
            }
        } catch (SecurityException error) {
            fail("Bluetooth-Berechtigung fehlt");
        } catch (RuntimeException error) {
            fail("Verbindungsstart fehlgeschlagen: " + safeMessage(error));
        }
    }

    @SuppressLint("MissingPermission")
    void disconnect() {
        closedByUser = true;
        stage = ConnectionStage.DISCONNECTED;
        detail = "Vom Nutzer getrennt";
        BluetoothGatt local = gatt;
        gatt = null;
        if (local != null) {
            try {
                local.disconnect();
            } catch (RuntimeException ignored) {
            }
            mainHandler.postDelayed(() -> closeGatt(local), 200L);
        }
        log("Verbindung zu " + candidate.name() + " getrennt");
        emit();
    }

    private final BluetoothGattCallback callback = new BluetoothGattCallback() {
        @Override
        @SuppressLint("MissingPermission")
        public void onConnectionStateChange(BluetoothGatt callbackGatt, int status, int newState) {
            if (closedByUser) {
                return;
            }
            if (status != BluetoothGatt.GATT_SUCCESS) {
                fail("GATT-Fehler " + status + " beim Verbindungsaufbau");
                return;
            }
            if (newState == BluetoothProfile.STATE_CONNECTED) {
                stage = ConnectionStage.NEGOTIATING;
                detail = "BLE verbunden, Verbindung wird optimiert";
                log("BLE verbunden; fordere hohe Priorität und MTU 247 an");
                emit();
                callbackGatt.requestConnectionPriority(BluetoothGatt.CONNECTION_PRIORITY_HIGH);
                boolean mtuRequested = callbackGatt.requestMtu(247);
                if (!mtuRequested) {
                    discoverServicesOnce(callbackGatt);
                } else {
                    mainHandler.postDelayed(() -> discoverServicesOnce(callbackGatt), 1_500L);
                }
            } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                stage = ConnectionStage.DISCONNECTED;
                detail = "Bluetooth-Verbindung beendet";
                log(candidate.name() + " wurde getrennt");
                if (gatt == callbackGatt) {
                    gatt = null;
                }
                closeGatt(callbackGatt);
                emit();
            }
        }

        @Override
        public void onMtuChanged(BluetoothGatt callbackGatt, int mtu, int status) {
            log(status == BluetoothGatt.GATT_SUCCESS
                    ? "MTU ausgehandelt: " + mtu
                    : "MTU-Anfrage abgelehnt; verwende Android-Standard");
            discoverServicesOnce(callbackGatt);
        }

        @Override
        public void onServicesDiscovered(BluetoothGatt callbackGatt, int status) {
            if (status != BluetoothGatt.GATT_SUCCESS) {
                fail("GATT-Dienste konnten nicht gelesen werden: " + status);
                return;
            }

            BluetoothGattService nintendoService =
                    callbackGatt.getService(JoyCon2Protocol.NINTENDO_SERVICE_UUID);
            if (nintendoService == null) {
                fail("Nintendo-Joy-Con-2-Dienst wurde nicht gefunden");
                return;
            }

            inputCharacteristic = nintendoService.getCharacteristic(JoyCon2Protocol.INPUT_REPORT_UUID);
            outputCharacteristic = findCharacteristic(
                    callbackGatt,
                    JoyCon2Protocol.WRITE_COMMAND_UUID
            );
            if (outputCharacteristic == null) {
                outputCharacteristic = firstWritableCharacteristic(nintendoService);
            }

            BluetoothGattService deviceInfo =
                    callbackGatt.getService(JoyCon2Protocol.DEVICE_INFORMATION_SERVICE_UUID);
            pnpCharacteristic = deviceInfo == null
                    ? null
                    : deviceInfo.getCharacteristic(JoyCon2Protocol.PNP_ID_UUID);

            if (inputCharacteristic == null) {
                fail("Input-Characteristic des Joy-Con 2 fehlt");
                return;
            }
            if (outputCharacteristic == null) {
                fail("Schreib-Characteristic für die Kalibrierungsanfrage fehlt");
                return;
            }

            log("Joy-Con-2-GATT-Dienst und Ein-/Ausgabekanäle gefunden");
            enableInputNotifications(callbackGatt);
        }

        @Override
        public void onDescriptorWrite(
                BluetoothGatt callbackGatt,
                BluetoothGattDescriptor descriptor,
                int status
        ) {
            if (!JoyCon2Protocol.CLIENT_CHARACTERISTIC_CONFIGURATION_UUID.equals(descriptor.getUuid())) {
                return;
            }
            if (status != BluetoothGatt.GATT_SUCCESS) {
                fail("Input-Benachrichtigungen konnten nicht aktiviert werden: " + status);
                return;
            }
            log("Input-Benachrichtigungen aktiv");
            sendCalibrationRead(callbackGatt);
        }

        @Override
        public void onCharacteristicChanged(
                BluetoothGatt callbackGatt,
                BluetoothGattCharacteristic characteristic,
                byte[] value
        ) {
            handleNotification(characteristic, value);
        }

        @Override
        @Deprecated
        public void onCharacteristicChanged(
                BluetoothGatt callbackGatt,
                BluetoothGattCharacteristic characteristic
        ) {
            handleNotification(characteristic, characteristic.getValue());
        }

        @Override
        public void onCharacteristicRead(
                BluetoothGatt callbackGatt,
                BluetoothGattCharacteristic characteristic,
                byte[] value,
                int status
        ) {
            handleCharacteristicRead(callbackGatt, characteristic, value, status);
        }

        @Override
        @Deprecated
        public void onCharacteristicRead(
                BluetoothGatt callbackGatt,
                BluetoothGattCharacteristic characteristic,
                int status
        ) {
            handleCharacteristicRead(callbackGatt, characteristic, characteristic.getValue(), status);
        }

        @Override
        public void onCharacteristicWrite(
                BluetoothGatt callbackGatt,
                BluetoothGattCharacteristic characteristic,
                int status
        ) {
            if (outputCharacteristic != null
                    && outputCharacteristic.getUuid().equals(characteristic.getUuid())
                    && status != BluetoothGatt.GATT_SUCCESS) {
                fail("Kalibrierungsanfrage wurde mit Status " + status + " abgelehnt");
            }
        }

        @Override
        public void onReadRemoteRssi(BluetoothGatt callbackGatt, int value, int status) {
            if (status == BluetoothGatt.GATT_SUCCESS) {
                rssi = value;
                emit();
            }
        }
    };

    @SuppressLint("MissingPermission")
    private synchronized void discoverServicesOnce(BluetoothGatt callbackGatt) {
        if (closedByUser || callbackGatt != gatt || discoveryRequested) {
            return;
        }
        discoveryRequested = true;
        stage = ConnectionStage.DISCOVERING;
        detail = "Joy-Con-2-Dienste werden gesucht";
        emit();
        if (!callbackGatt.discoverServices()) {
            fail("Android konnte die GATT-Dienstsuche nicht starten");
        }
    }

    @SuppressLint("MissingPermission")
    private void enableInputNotifications(BluetoothGatt callbackGatt) {
        stage = ConnectionStage.SUBSCRIBING;
        detail = "Input-Stream wird aktiviert";
        emit();

        if (!callbackGatt.setCharacteristicNotification(inputCharacteristic, true)) {
            fail("Android konnte lokale Input-Benachrichtigungen nicht aktivieren");
            return;
        }
        BluetoothGattDescriptor cccd = inputCharacteristic.getDescriptor(
                JoyCon2Protocol.CLIENT_CHARACTERISTIC_CONFIGURATION_UUID
        );
        if (cccd == null) {
            fail("CCCD-Descriptor des Input-Kanals fehlt");
            return;
        }

        int result;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            result = callbackGatt.writeDescriptor(
                    cccd,
                    BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
            );
        } else {
            cccd.setValue(BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE);
            result = callbackGatt.writeDescriptor(cccd)
                    ? BluetoothStatusCodes.SUCCESS
                    : BluetoothStatusCodes.ERROR_UNKNOWN;
        }
        if (result != BluetoothStatusCodes.SUCCESS) {
            fail("CCCD-Schreibvorgang konnte nicht gestartet werden: " + result);
        }
    }

    @SuppressLint("MissingPermission")
    private void sendCalibrationRead(BluetoothGatt callbackGatt) {
        byte[] command = JoyCon2Protocol.calibrationReadCommand();
        int result;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            result = callbackGatt.writeCharacteristic(
                    outputCharacteristic,
                    command,
                    BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE
            );
        } else {
            outputCharacteristic.setWriteType(BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE);
            outputCharacteristic.setValue(command);
            result = callbackGatt.writeCharacteristic(outputCharacteristic)
                    ? BluetoothStatusCodes.SUCCESS
                    : BluetoothStatusCodes.ERROR_UNKNOWN;
        }

        if (result != BluetoothStatusCodes.SUCCESS) {
            fail("Kalibrierungsanfrage konnte nicht gesendet werden: " + result);
            return;
        }

        stage = ConnectionStage.READY;
        detail = "Input-Stream bereit";
        log("Kalibrierungs-Leseanfrage gesendet; warte auf Eingaben");
        emit();

        mainHandler.postDelayed(() -> {
            BluetoothGatt local = gatt;
            if (local == null || stage != ConnectionStage.READY) {
                return;
            }
            try {
                if (pnpCharacteristic != null) {
                    if (!local.readCharacteristic(pnpCharacteristic)) {
                        local.readRemoteRssi();
                    }
                } else {
                    local.readRemoteRssi();
                }
            } catch (SecurityException ignored) {
            }
        }, 250L);
    }

    private void handleNotification(
            BluetoothGattCharacteristic characteristic,
            byte[] value
    ) {
        if (!JoyCon2Protocol.INPUT_REPORT_UUID.equals(characteristic.getUuid())
                || value == null) {
            return;
        }

        byte[] report = Arrays.copyOf(value, value.length);
        lastReportHex = JoyCon2ReportDecoder.toHex(report);
        Optional<JoyCon2InputState> decoded = decoder.decode(report);
        if (decoded.isEmpty()) {
            return;
        }

        long now = System.nanoTime();
        if (lastFrameNanos != 0L) {
            double deltaSeconds = (now - lastFrameNanos) / 1_000_000_000.0;
            if (deltaSeconds > 0.0) {
                double instantRate = 1.0 / deltaSeconds;
                reportRateHz = reportRateHz == 0.0
                        ? instantRate
                        : reportRateHz * 0.85 + instantRate * 0.15;
            }
        }
        lastFrameNanos = now;
        frameCount++;
        inputState = decoded.get();
        JoyCon2Side reportSide = inputState.inferredSide();
        if (reportSide != JoyCon2Side.UNKNOWN && reportSide != side) {
            side = reportSide;
            log("Controller-Seite anhand des Live-Reports erkannt: " + side.germanLabel());
        }
        emit();
    }

    @SuppressLint("MissingPermission")
    private void handleCharacteristicRead(
            BluetoothGatt callbackGatt,
            BluetoothGattCharacteristic characteristic,
            byte[] value,
            int status
    ) {
        if (JoyCon2Protocol.PNP_ID_UUID.equals(characteristic.getUuid())
                && status == BluetoothGatt.GATT_SUCCESS
                && value != null
                && value.length >= 5) {
            int productId = (value[3] & 0xFF) | ((value[4] & 0xFF) << 8);
            JoyCon2Side detected = JoyCon2Side.fromProductId(productId);
            if (detected != JoyCon2Side.UNKNOWN) {
                side = detected;
                log("Controller-Seite über PnP-ID erkannt: " + side.germanLabel());
                emit();
            }
        }
        try {
            callbackGatt.readRemoteRssi();
        } catch (SecurityException ignored) {
        }
    }

    private static BluetoothGattCharacteristic findCharacteristic(
            BluetoothGatt callbackGatt,
            java.util.UUID uuid
    ) {
        for (BluetoothGattService service : callbackGatt.getServices()) {
            BluetoothGattCharacteristic characteristic = service.getCharacteristic(uuid);
            if (characteristic != null) {
                return characteristic;
            }
        }
        return null;
    }

    private static BluetoothGattCharacteristic firstWritableCharacteristic(
            BluetoothGattService service
    ) {
        for (BluetoothGattCharacteristic characteristic : service.getCharacteristics()) {
            int properties = characteristic.getProperties();
            if ((properties & BluetoothGattCharacteristic.PROPERTY_WRITE) != 0
                    || (properties & BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE) != 0) {
                return characteristic;
            }
        }
        return null;
    }

    private void fail(String message) {
        if (closedByUser) {
            return;
        }
        stage = ConnectionStage.ERROR;
        detail = message;
        log("Fehler: " + message);
        BluetoothGatt local = gatt;
        gatt = null;
        if (local != null) {
            closeGatt(local);
        }
        emit();
    }

    @SuppressLint("MissingPermission")
    private static void closeGatt(BluetoothGatt callbackGatt) {
        try {
            callbackGatt.close();
        } catch (SecurityException ignored) {
        } catch (RuntimeException ignored) {
        }
    }

    private void log(String message) {
        mainHandler.post(() -> listener.onLog(message));
    }

    private void emit() {
        ControllerSnapshot snapshot = new ControllerSnapshot(
                candidate.address(),
                candidate.name(),
                side,
                stage,
                detail,
                rssi,
                frameCount,
                reportRateHz,
                inputState,
                lastReportHex
        );
        mainHandler.post(() -> listener.onSessionChanged(snapshot));
    }

    private static String safeMessage(Throwable error) {
        return error.getMessage() == null ? error.getClass().getSimpleName() : error.getMessage();
    }
}
