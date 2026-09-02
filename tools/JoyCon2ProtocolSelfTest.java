package dev.joycon2.bridge.protocol;

public final class JoyCon2ProtocolSelfTest {
    public static void main(String[] args) {
        JoyCon2ReportDecoder decoder = new JoyCon2ReportDecoder();
        byte[] report = inputReport(1998, 1000, 2047, 0, (1L << 3) | (1L << 17));
        JoyCon2InputState state = decoder.decode(report)
                .orElseThrow(() -> new AssertionError("Valid input frame was rejected"));

        require(state.isPressed(JoyCon2Button.A), "A button missing");
        require(state.isPressed(JoyCon2Button.DPAD_UP), "D-pad up missing");
        require(!state.isPressed(JoyCon2Button.B), "B button must be released");
        require(state.leftX() == 1998, "Left X decode failed");
        require(state.leftY() == 1000, "Left Y decode failed");
        require(state.rightX() == 1998, "Sentinel must map to neutral");
        require(state.rightY() == 1998, "Zero axis must map to neutral");
        require(state.inferredSide() == JoyCon2Side.LEFT, "Active left stick must infer left side");

        JoyCon2InputState rightState = decoder.decode(
                inputReport(0x7FF, 0x7FF, 1605, 3259, (1L << 6) | (1L << 7))
        ).orElseThrow(() -> new AssertionError("Valid right input frame was rejected"));
        require(rightState.inferredSide() == JoyCon2Side.RIGHT,
                "Active right stick must infer right side");
        require(rightState.isPressed(JoyCon2Button.R), "R button missing");
        require(rightState.isPressed(JoyCon2Button.ZR), "ZR button missing");

        byte[] prefixed = new byte[report.length + 1];
        prefixed[0] = (byte) 0xA1;
        System.arraycopy(report, 0, prefixed, 1, report.length);
        require(decoder.decode(prefixed).orElseThrow().hadAttPrefix(), "A1 prefix not detected");

        byte[] rollingA1 = inputReport(
                746,
                3249,
                0x7FF,
                0x7FF,
                JoyCon2Button.DPAD_UP.mask() | JoyCon2Button.ZL.mask()
        );
        rollingA1[0] = (byte) 0xA1;
        JoyCon2InputState rollingState = decoder.decode(rollingA1).orElseThrow();
        require(!rollingState.hadAttPrefix(), "Native 62-byte A1 frame was shifted");
        require(rollingState.isPressed(JoyCon2Button.DPAD_UP), "Rolling A1 lost D-pad up");
        require(rollingState.isPressed(JoyCon2Button.ZL), "Rolling A1 lost ZL");
        require(!rollingState.isPressed(JoyCon2Button.DPAD_RIGHT),
                "Rolling A1 created phantom D-pad right");

        byte[] subcommand = new byte[16];
        subcommand[0] = 0x21;
        require(decoder.decode(subcommand).isEmpty(), "Subcommand reply must not decode as input");
        require(decoder.decode(new byte[7]).isEmpty(), "Short frame must be rejected");

        require(
                JoyCon2Protocol.sideFromManufacturerData(
                        new byte[]{0x01, 0x00, 0x03, 0x7E, 0x06, 0x20}
                ) == JoyCon2Side.LEFT,
                "Left advertisement not recognized"
        );
        require(
                JoyCon2Protocol.calibrationReadCommand().length == 16,
                "Calibration command length changed"
        );

        System.out.println("Joy-Con 2 protocol self-test: PASS");
    }

    private static void require(boolean condition, String message) {
        if (!condition) {
            throw new AssertionError(message);
        }
    }

    private static byte[] inputReport(int lx, int ly, int rx, int ry, long buttons) {
        byte[] report = new byte[62];
        report[0] = 0x08;
        report[4] = (byte) buttons;
        report[5] = (byte) (buttons >> 8);
        report[6] = (byte) (buttons >> 16);
        report[7] = (byte) (buttons >> 24);
        packStick(report, 10, lx, ly);
        packStick(report, 13, rx, ry);
        return report;
    }

    private static void packStick(byte[] target, int offset, int x, int y) {
        target[offset] = (byte) x;
        target[offset + 1] = (byte) (((x >> 8) & 0x0F) | ((y & 0x0F) << 4));
        target[offset + 2] = (byte) (y >> 4);
    }
}
