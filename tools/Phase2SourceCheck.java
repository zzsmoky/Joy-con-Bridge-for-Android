import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/** Phase 2 architectural invariants that can be checked without Android tooling. */
public final class Phase2SourceCheck {
    private Phase2SourceCheck() {
    }

    public static void main(String[] args) throws Exception {
        Path root = args.length == 0
                ? Path.of(".").toAbsolutePath().normalize()
                : Path.of(args[0]).toAbsolutePath().normalize();

        String manifest = read(root, "app/src/main/AndroidManifest.xml");
        require(manifest, "android.permission.FOREGROUND_SERVICE_CONNECTED_DEVICE",
                "connected-device foreground permission");
        require(manifest, "android:foregroundServiceType=\"connectedDevice\"",
                "connected-device foreground service");
        require(manifest, "rikka.shizuku.ShizukuProvider", "Shizuku provider");

        String build = read(root, "app/build.gradle.kts");
        require(build, "dev.rikka.shizuku:api", "Shizuku API dependency");
        require(build, "dev.rikka.shizuku:provider", "Shizuku provider dependency");
        require(build, "aidl = true", "AIDL build feature");
        String service = read(root,
                "app/src/main/java/dev/joycon2/bridge/service/BridgeService.java");
        require(service, "new JoyCon2BleManager(this, this)", "service-owned BLE manager");
        require(service, "new ShizukuGamepadBackend", "service-owned output backend");
        require(service, "startForeground", "foreground lifetime");

        String backend = read(root,
                "app/src/main/java/dev/joycon2/bridge/output/ShizukuGamepadBackend.java");
        require(backend, "Shizuku.bindUserService", "Shizuku UserService binding");
        require(backend, "FRAME_INTERVAL_MS = 16L", "analog frame rate limit");
        require(backend, "service.startVirtualGamepad()", "virtual gamepad registration");
        require(backend, "service.sendHidReport", "HID report forwarding");
        require(backend, "service.stopVirtualGamepad()", "virtual gamepad removal");

        String injector = read(root,
                "app/src/main/java/dev/joycon2/bridge/output/ShizukuInputService.java");
        require(injector, "HID_BINARY = \"/system/bin/hid\"", "Android HID command");
        require(injector, "new ProcessBuilder(HID_BINARY, \"-\")", "interactive HID process");
        require(injector, "InputDevice.SOURCE_GAMEPAD", "gamepad key source");
        require(injector, "InputDevice.SOURCE_JOYSTICK", "joystick motion source");

        String aidl = read(root,
                "app/src/main/aidl/dev/joycon2/bridge/output/IInputInjectionService.aidl");
        require(aidl, "void destroy() = 16777114", "Shizuku destroy transaction");
        require(aidl, "String startVirtualGamepad()", "HID registration transaction");
        require(aidl, "boolean sendHidReport", "HID report transaction");
        require(aidl, "void stopVirtualGamepad()", "HID removal transaction");

        String descriptor = read(root,
                "app/src/main/java/dev/joycon2/bridge/output/HidGamepadDescriptor.java");
        require(descriptor, "Usage (Game Pad)", "HID gamepad collection");
        require(descriptor, "Joy-Con 2 Bridge Virtual Gamepad", "enumerated device name");
        String report = read(root,
                "app/src/main/java/dev/joycon2/bridge/output/HidGamepadReport.java");
        require(report, "REPORT_LENGTH = 10", "fixed HID input report");
        require(report, "JoyCon2Button.ZR", "trigger button mapping");
        String stickResponse = read(root,
                "app/src/main/java/dev/joycon2/bridge/output/StickResponse.java");
        require(stickResponse, "DEAD_ZONE = 0.16f", "Joy-Con output dead zone");
        require(stickResponse, "CURVE_EXPONENT = 1.35", "progressive stick curve");

        String decoder = read(root,
                "app/src/main/java/dev/joycon2/bridge/protocol/JoyCon2ReportDecoder.java");
        require(decoder, "report.length == BLE_REPORT_LENGTH + 1",
                "rolling A1 framing guard");

        String workflow = read(root, ".github/workflows/android.yml");
        require(workflow, "joycon2-bridge-phase2-uhid-debug", "Phase 2 APK artifact");
        require(workflow, "apksigner", "APK signature verification");
        read(root, "docs/PHASE_2.md");

        System.out.println("Phase 2 source check: PASS");
    }

    private static String read(Path root, String relative) throws Exception {
        Path path = root.resolve(relative);
        if (!Files.isRegularFile(path)) {
            throw new IllegalStateException("Required file missing: " + path);
        }
        return Files.readString(path, StandardCharsets.UTF_8);
    }

    private static void require(String value, String needle, String label) {
        if (!value.contains(needle)) {
            throw new IllegalStateException("Missing " + label + ": " + needle);
        }
    }
}
