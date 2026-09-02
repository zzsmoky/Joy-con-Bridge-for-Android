# Phase 1 — BLE transport and input diagnostics

## Goal

Prove on the target Android phone that one left and one right Joy-Con 2 can be
discovered, connected simultaneously, initialized without pairing in Settings,
and decoded into stable controller state.

## Included

1. Android 12+ Nearby Devices permission flow.
2. Bounded 12-second BLE scan; no continuous background scanning.
3. Candidate detection from the Joy-Con 2 name, Nintendo custom service, or
   Nintendo manufacturer advertisement.
4. Independent GATT session per controller, capped at two.
5. High-priority connection request, MTU negotiation, service discovery,
   notification subscription, and explicit stage/error reporting.
6. One calibration SPI read after notification subscription. Public protocol
   implementations report that this is needed before non-zero live input flows.
7. Defensive decode of button bits and packed 12-bit axes, including optional
   `0xA1` prefix and unused-axis sentinel handling.
8. Live raw report, buttons, axes, RSSI, frame count, smoothed report rate, and
   a bounded diagnostic log.

## Excluded

- Virtual gamepad output to other Android apps.
- Accessibility touch mapping, Shizuku/root `uinput`, or external HID hardware.
- Background/foreground connection service and automatic reconnect.
- Rumble, LEDs, mouse sensor, IMU, NFC, IR, firmware, keys, and pairing-data
  writes.
- Play Store packaging and production telemetry.

## Hardware acceptance checklist

Run on the Samsung Galaxy S24 Ultra with Bluetooth enabled:

- [ ] Nearby Devices permission is requested once and status becomes green.
- [ ] Left Joy-Con 2 appears during a bounded scan while SYNC is held.
- [ ] Its connection advances through BLE setup, GATT discovery, input setup,
      then `BEREIT` without an Android Settings bond.
- [ ] A/B/X/Y or D-pad changes the pressed-button list with no stuck edges.
- [ ] The active stick changes smoothly and returns near the neutral value.
- [ ] Frame counter rises continuously and report rate becomes non-zero.
- [ ] The same checks pass for the right Joy-Con 2.
- [ ] Both cards remain `BEREIT` together and continue receiving input.
- [ ] Disconnecting in the app releases each controller cleanly.
- [ ] Any failure stage and numeric GATT code are visible in the log.

## Exit criterion

Phase 1 is accepted after one complete dual-controller run and one exported
screenshot or copied log showing both sessions ready. Hardware-specific findings
become fixtures or transport fixes before Phase 2 begins.
