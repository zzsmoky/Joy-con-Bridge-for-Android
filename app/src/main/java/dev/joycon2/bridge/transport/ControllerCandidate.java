package dev.joycon2.bridge.transport;

import android.bluetooth.BluetoothDevice;

import dev.joycon2.bridge.protocol.JoyCon2Side;

public final class ControllerCandidate {
    private final BluetoothDevice device;
    private final String address;
    private final String name;
    private final int rssi;
    private final JoyCon2Side side;

    ControllerCandidate(
            BluetoothDevice device,
            String address,
            String name,
            int rssi,
            JoyCon2Side side
    ) {
        this.device = device;
        this.address = address;
        this.name = name;
        this.rssi = rssi;
        this.side = side;
    }

    BluetoothDevice device() {
        return device;
    }

    public String address() {
        return address;
    }

    public String name() {
        return name;
    }

    public int rssi() {
        return rssi;
    }

    public JoyCon2Side side() {
        return side;
    }
}
