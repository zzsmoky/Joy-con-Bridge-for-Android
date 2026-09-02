package dev.joycon2.bridge.protocol;

import java.util.Locale;
import java.util.Optional;

public final class JoyCon2ReportDecoder {
    public static final int SENTINEL_STICK_VALUE = 0x7FF;
    public static final int BLE_REPORT_LENGTH = 62;

    public Optional<JoyCon2InputState> decode(byte[] report) {
        if (report == null || report.length < 8) {
            return Optional.empty();
        }

        /*
         * Native Android GATT notifications are 62 bytes and do not contain a
         * HIDP 0xA1 prefix. Their first byte is a rolling controller value and
         * naturally becomes 0xA1 from time to time. Only accept the prefix for
         * an explicitly wrapped 63-byte capture; otherwise every such rollover
         * shifts the button bitmap and creates phantom D-pad presses.
         */
        boolean hasAttPrefix = report.length == BLE_REPORT_LENGTH + 1
                && unsigned(report[0]) == 0xA1;
        int offset = hasAttPrefix ? 1 : 0;
        if (report.length < offset + 8) {
            return Optional.empty();
        }

        // 0x21 identifies the calibration/subcommand reply, not a live input frame.
        if (unsigned(report[offset]) == 0x21) {
            return Optional.empty();
        }

        long buttons = unsigned(report[offset + 4])
                | ((long) unsigned(report[offset + 5]) << 8)
                | ((long) unsigned(report[offset + 6]) << 16)
                | ((long) unsigned(report[offset + 7]) << 24);

        int rawLeftX = 0;
        int rawLeftY = 0;
        int rawRightX = 0;
        int rawRightY = 0;
        int leftX = JoyCon2InputState.STICK_CENTER;
        int leftY = JoyCon2InputState.STICK_CENTER;
        int rightX = JoyCon2InputState.STICK_CENTER;
        int rightY = JoyCon2InputState.STICK_CENTER;

        if (report.length >= offset + 16) {
            rawLeftX = unpackX(report, offset + 10);
            rawLeftY = unpackY(report, offset + 10);
            rawRightX = unpackX(report, offset + 13);
            rawRightY = unpackY(report, offset + 13);
            leftX = neutralizeMissingAxis(rawLeftX);
            leftY = neutralizeMissingAxis(rawLeftY);
            rightX = neutralizeMissingAxis(rawRightX);
            rightY = neutralizeMissingAxis(rawRightY);
        }

        return Optional.of(new JoyCon2InputState(
                buttons,
                rawLeftX,
                rawLeftY,
                rawRightX,
                rawRightY,
                leftX,
                leftY,
                rightX,
                rightY,
                hasAttPrefix
        ));
    }

    private static int unpackX(byte[] report, int start) {
        return unsigned(report[start]) | ((unsigned(report[start + 1]) & 0x0F) << 8);
    }

    private static int unpackY(byte[] report, int start) {
        return (unsigned(report[start + 1]) >> 4) | (unsigned(report[start + 2]) << 4);
    }

    private static int neutralizeMissingAxis(int value) {
        if (value == 0 || value == SENTINEL_STICK_VALUE) {
            return JoyCon2InputState.STICK_CENTER;
        }
        return value;
    }

    private static int unsigned(byte value) {
        return value & 0xFF;
    }

    public static String toHex(byte[] data) {
        if (data == null || data.length == 0) {
            return "—";
        }
        StringBuilder result = new StringBuilder(data.length * 3);
        for (int i = 0; i < data.length; i++) {
            if (i > 0) {
                result.append(' ');
            }
            result.append(String.format(Locale.ROOT, "%02X", unsigned(data[i])));
        }
        return result.toString();
    }
}
