package dev.joycon2.bridge.output;

/** HID descriptor and command encoding for the kernel-backed virtual Android gamepad. */
public final class HidGamepadDescriptor {
    public static final int DEVICE_ID = 2202;
    public static final String DEVICE_NAME = "Joy-Con 2 Bridge Virtual Gamepad";

    public static final int COMBINED_DEVICE_ID = 7001;
    public static final int LEFT_DEVICE_ID = 7002;
    public static final int RIGHT_DEVICE_ID = 7003;
    public static final String COMBINED_DEVICE_NAME = "Joy-Con Bridge Combined Gamepad";
    public static final String LEFT_DEVICE_NAME = "Joy-Con Bridge L Sideways";
    public static final String RIGHT_DEVICE_NAME = "Joy-Con Bridge R Sideways";

    private static final int VENDOR_ID = 0x1209;
    private static final int PRODUCT_ID = 0x2202;

    /*
     * Report 1: 16 buttons, an eight-way hat with null state, X/Y/Z/Rz sticks,
     * and Brake/Accelerator trigger axes. This follows Android's generic gamepad mapping.
     */
    private static final int[] REPORT_DESCRIPTOR = {
            0x05, 0x01,       // Usage Page (Generic Desktop)
            0x09, 0x05,       // Usage (Game Pad)
            0xA1, 0x01,       // Collection (Application)
            0x85, 0x01,       //   Report ID (1)
            0x05, 0x09,       //   Usage Page (Button)
            0x19, 0x01,       //   Usage Minimum (1)
            0x29, 0x10,       //   Usage Maximum (16)
            0x15, 0x00,       //   Logical Minimum (0)
            0x25, 0x01,       //   Logical Maximum (1)
            0x75, 0x01,       //   Report Size (1)
            0x95, 0x10,       //   Report Count (16)
            0x81, 0x02,       //   Input (Data, Variable, Absolute)
            0x05, 0x01,       //   Usage Page (Generic Desktop)
            0x09, 0x39,       //   Usage (Hat Switch)
            0x15, 0x00,       //   Logical Minimum (0)
            0x25, 0x07,       //   Logical Maximum (7)
            0x35, 0x00,       //   Physical Minimum (0)
            0x46, 0x3B, 0x01, //   Physical Maximum (315)
            0x65, 0x14,       //   Unit (English Rotation, Degrees)
            0x75, 0x04,       //   Report Size (4)
            0x95, 0x01,       //   Report Count (1)
            0x81, 0x42,       //   Input (Data, Variable, Absolute, Null State)
            0x65, 0x00,       //   Unit (None)
            0x75, 0x04,       //   Report Size (4)
            0x95, 0x01,       //   Report Count (1)
            0x81, 0x03,       //   Input (Constant, Variable, Absolute)
            0x09, 0x01,       //   Usage (Pointer)
            0xA1, 0x00,       //   Collection (Physical)
            0x09, 0x30,       //     Usage (X)
            0x09, 0x31,       //     Usage (Y)
            0x09, 0x32,       //     Usage (Z)
            0x09, 0x35,       //     Usage (Rz)
            0x15, 0x00,       //     Logical Minimum (0)
            0x26, 0xFF, 0x00, //     Logical Maximum (255)
            0x35, 0x00,       //     Physical Minimum (0)
            0x46, 0xFF, 0x00, //     Physical Maximum (255)
            0x75, 0x08,       //     Report Size (8)
            0x95, 0x04,       //     Report Count (4)
            0x81, 0x02,       //     Input (Data, Variable, Absolute)
            0x05, 0x02,       //     Usage Page (Simulation Controls)
            0x09, 0xC5,       //     Usage (Brake)
            0x09, 0xC4,       //     Usage (Accelerator)
            0x15, 0x00,       //     Logical Minimum (0)
            0x26, 0xFF, 0x00, //     Logical Maximum (255)
            0x35, 0x00,       //     Physical Minimum (0)
            0x46, 0xFF, 0x00, //     Physical Maximum (255)
            0x75, 0x08,       //     Report Size (8)
            0x95, 0x02,       //     Report Count (2)
            0x81, 0x02,       //     Input (Data, Variable, Absolute)
            0xC0,             //   End Collection
            0xC0              // End Collection
    };

    private HidGamepadDescriptor() {
    }

    public static String registerCommand() {
        return registerCommand(DEVICE_ID, DEVICE_NAME, PRODUCT_ID);
    }

    public static String registerCommand(int deviceId, String deviceName, int productId) {
        StringBuilder command = new StringBuilder(640);
        command.append("{\"id\":").append(deviceId)
                .append(",\"command\":\"register\"")
                .append(",\"name\":\"").append(deviceName).append('"')
                .append(",\"vid\":").append(VENDOR_ID)
                .append(",\"pid\":").append(productId & 0xFFFF)
                .append(",\"bus\":\"bluetooth\",\"descriptor\":");
        appendUnsignedBytes(command, REPORT_DESCRIPTOR);
        return command.append('}').toString();
    }

    public static String reportCommand(byte[] report) {
        return reportCommand(DEVICE_ID, report);
    }

    public static String reportCommand(int deviceId, byte[] report) {
        if (report == null || report.length != HidGamepadReport.REPORT_LENGTH) {
            throw new IllegalArgumentException("Ungültige HID-Reportlänge");
        }
        StringBuilder command = new StringBuilder(128);
        command.append("{\"id\":").append(deviceId)
                .append(",\"command\":\"report\",\"report\":");
        appendUnsignedBytes(command, report);
        return command.append('}').toString();
    }

    private static void appendUnsignedBytes(StringBuilder target, int[] values) {
        target.append('[');
        for (int index = 0; index < values.length; index++) {
            if (index > 0) {
                target.append(',');
            }
            target.append(values[index] & 0xFF);
        }
        target.append(']');
    }

    private static void appendUnsignedBytes(StringBuilder target, byte[] values) {
        target.append('[');
        for (int index = 0; index < values.length; index++) {
            if (index > 0) {
                target.append(',');
            }
            target.append(Byte.toUnsignedInt(values[index]));
        }
        target.append(']');
    }
}
