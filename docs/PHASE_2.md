# Phase 2 — Shizuku controller output

## Goal

Keep both proven Joy-Con 2 BLE sessions alive while a Switch emulator is in the
foreground, merge them into one controller state, and expose one enumerated
Android gamepad without rooting the Galaxy S24 Ultra.

## Included

1. A connected-device foreground service owns BLE and output lifetimes.
2. Left and right reports are merged into one immutable gamepad state.
3. Nintendo Y axes are converted to Android's direction convention.
4. D-pad buttons are encoded as an eight-way HID hat.
5. ZL/ZR are encoded as standard buttons plus Brake/Accelerator axes.
6. A Shizuku UserService owns a `/dev/uinput` gamepad as shell UID.
7. The uinput capabilities create a named gamepad with `FF_RUMBLE` support.
8. Reports are rate-limited near 60 Hz and always use the newest state.
9. Disabling output sends neutral state and destroys the uinput device.
10. A 16% output dead zone and progressive curve reduce stick drift and sensitivity.
11. Native 62-byte BLE reports cannot be shifted by a rolling `0xA1` first byte.

## Safety and scope

This phase reads controller input, emits transient virtual input events, and translates
game rumble effects to Nintendo HID output reports. It
does not write Joy-Con firmware, pairing keys, calibration storage, NFC, IR, or
other controller data. While enabled, the virtual gamepad is visible system-wide;
pause the output before entering passwords or using unrelated apps.

## Emulator acceptance checklist

On the Galaxy S24 Ultra running Android 16:

- [ ] Start Shizuku and confirm its service is running.
- [ ] Install/open the bridge and grant Nearby Devices plus Shizuku access.
- [ ] Connect both Joy-Con 2; both controller cards become `BEREIT`.
- [ ] Enable output; the output card becomes `Controller-Ausgabe aktiv`.
- [ ] Switch to the emulator without dismissing the bridge notification.
- [ ] Select `Joy-Con 2 Bridge Virtual Gamepad` in the emulator.
- [ ] In controller mapping, A/B/X/Y and D-pad produce distinct bindings.
- [ ] L/R, ZL/ZR, +, −, L3, and R3 produce distinct bindings.
- [ ] Both sticks reach all four directions and return to neutral.
- [ ] No button remains held after release.
- [ ] Returning to the bridge and stopping output releases all controls.

## Exit criterion

Phase 2 passes when the selected Switch emulator enumerates the named gamepad,
accepts every required binding, and one game can be controlled with both
Joy-Con 2. The bridge's virtual-report counter must rise while output is active. A
registration failure is shown with the `/dev/uinput` diagnostic from the Shizuku
process and is separate from the already-proven BLE decoder.
