package dev.joycon2.bridge.protocol;

import java.util.Locale;
import java.util.UUID;

public final class JoyCon2Protocol {
    public static final UUID NINTENDO_SERVICE_UUID =
            UUID.fromString("ab7de9be-89fe-49ad-828f-118f09df7fd0");
    public static final UUID INPUT_REPORT_UUID =
            UUID.fromString("ab7de9be-89fe-49ad-828f-118f09df7fd2");
    public static final UUID WRITE_COMMAND_UUID =
            UUID.fromString("649d4ac9-8eb7-4e6c-af44-1ea54fe5f005");
    public static final UUID CLIENT_CHARACTERISTIC_CONFIGURATION_UUID =
            UUID.fromString("00002902-0000-1000-8000-00805f9b34fb");
    public static final UUID DEVICE_INFORMATION_SERVICE_UUID =
            UUID.fromString("0000180a-0000-1000-8000-00805f9b34fb");
    public static final UUID PNP_ID_UUID =
            UUID.fromString("00002a50-0000-1000-8000-00805f9b34fb");

    public static final int NINTENDO_COMPANY_ID = 0x0553;

    private static final byte[] CALIBRATION_READ = {
            0x21, 0x01, 0x00, 0x10,
            0x00, 0x18, 0x00, 0x00,
            (byte) 0xD0, 0x3D, 0x06, 0x00,
            0x00, 0x00, 0x00, 0x00
    };

    private JoyCon2Protocol() {
    }

    public static byte[] calibrationReadCommand() {
        return CALIBRATION_READ.clone();
    }

    public static boolean isJoyCon2Name(String name) {
        if (name == null) {
            return false;
        }
        String normalized = name.toLowerCase(Locale.ROOT)
                .replace("‑", "-")
                .replace("–", "-");
        return normalized.contains("joy-con 2")
                || normalized.contains("joycon 2")
                || normalized.contains("joy con 2");
    }

    /**
     * Nintendo's BLE manufacturer payload starts with 01 00 03 7E. Android
     * removes the two-byte company id before returning this array.
     */
    public static boolean looksLikeJoyCon2ManufacturerData(byte[] data) {
        return data != null
                && data.length >= 6
                && unsigned(data[0]) == 0x01
                && unsigned(data[1]) == 0x00
                && unsigned(data[2]) == 0x03
                && unsigned(data[3]) == 0x7E;
    }

    public static JoyCon2Side sideFromManufacturerData(byte[] data) {
        if (!looksLikeJoyCon2ManufacturerData(data)) {
            return JoyCon2Side.UNKNOWN;
        }
        int littleEndian = unsigned(data[4]) | (unsigned(data[5]) << 8);
        JoyCon2Side side = JoyCon2Side.fromProductId(littleEndian);
        if (side != JoyCon2Side.UNKNOWN) {
            return side;
        }
        int bigEndian = (unsigned(data[4]) << 8) | unsigned(data[5]);
        return JoyCon2Side.fromProductId(bigEndian);
    }

    private static int unsigned(byte value) {
        return value & 0xFF;
    }
}
