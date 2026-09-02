# Verification

验证日期：2026-08-31，目标设备 Lenovo TB323FU，Android 16 / API 36。

## 自动验证

执行：

```text
:app:testDebugUnitTest :app:lintDebug :app:assembleDebug
```

结果：`BUILD SUCCESSFUL`。

覆盖内容：

- 原有 HID 报告编码测试；
- 组合模式 ABXY、肩键、扳机、十字帽映射；
- 左横握摇杆/方向键旋转；
- 右横握摇杆/ABXY 旋转；
- JNI arm64-v8a 以 `-Wall -Wextra -Werror` 编译；
- Nintendo HD Rumble 振幅编码边界与中等强度单测；
- Android Lint：0 errors；
- APK 内含 `lib/arm64-v8a/libjoycon_evdev.so`；
- APK Signature Scheme v2 验证通过。

构建产物 SHA-256：

```text
174FA8765181E19194FB44A68D39FE215463F1AC02E45EE48E11AB76F434D973
```

注意：任何源代码变化后散列都会变化，应以最终构建旁的实际 `Get-FileHash` 结果为准。

## 目标设备只读探测

`adb shell getevent -i` 确认：

- Joy-Con (L) 主节点：VID 057e、PID 2006、ABS_X/ABS_Y、方向键与左侧按键；
- Joy-Con (R) 主节点：VID 057e、PID 2007、ABS_RX/ABS_RY、ABXY 与右侧按键；
- 两侧 IMU 节点名称带 `(IMU)` 且没有游戏按键能力；
- `/dev/uinput` 为 `uhid:uhid`、模式 `0660`，Shizuku shell UID 属于 `uhid` 组；
- 内核启用 `CONFIG_INPUT_UINPUT=y`。

动态发现代码没有保存探测时的 event 编号。

## APK 真机冒烟测试

- 版本 `1.4.0` 的包名为 `dev.joycon2.bridge`；
- Activity 冷启动成功，无 Java crash、JNI load crash 或 FGS SecurityException；
- special-use 前台服务状态为 `isForeground=true`；
- 横屏 3040×1904 Material 3 UI 能按兼容、组合、原生的顺序显示三种模式；
- 简体中文、English、日本語均完成真机切换；默认跟随系统，未知语言回退英语；
- 日志默认由开发者模式隐藏；开启后服务状态与运行日志内容固定为英语；
- 底部“关于”显示版本、GitHub 源码入口与 `zzsmoky` 署名；
- 启动图标使用左右手柄桥形标志，并提供 Android 主题单色图标；
- 未取得用户 Shizuku 授权时保持“原生双手柄”，EVIOCGRAB 显示已释放。

## FF_RUMBLE 真机验证

- 组合模式的 `Joy-Con Bridge Combined Gamepad` 显示
  `KEYBOARD | GAMEPAD | JOYSTICK | VIBRATOR | EXTERNAL`；
- 兼容模式的左右两个虚拟手柄均显示 `VIBRATOR`，各自声明 `FF_RUMBLE`；
- 开发者测试按钮通过 Android `InputDevice.getVibrator()` 发起 600 ms 效果，不再绕过
  虚拟设备直接发送；
- 组合模式捕获到 `strong=46080, weak=46080, durationMs=600` 以及停止事件；
- 兼容模式左右接收线程分别捕获同一效果及停止事件；
- 捕获后的幅值通过与上游 `hid-nintendo` 相同的 0..1003 振幅表编码，并以 50 ms
  周期发送 Nintendo HD Rumble 报告。

## 需要用户授权的最终验收

- [ ] 在 Shizuku 弹窗批准本应用；
- [ ] 组合模式显示 L/R 均已连接、EVIOCGRAB 已接管；
- [ ] `Joy-Con Bridge Combined Gamepad` 出现在 Android/游戏手柄列表；
- [ ] 原生模式移除虚拟设备，物理 L/R 恢复；
- [ ] 兼容模式出现 `Joy-Con Bridge L Sideways` 与 `Joy-Con Bridge R Sideways`；
- [ ] 实际游戏确认横握摇杆方向、ABXY 位置和 SL/SR；
- [ ] 断开并重连任一 Joy-Con 后自动恢复，event 编号变化不影响工作。
- [ ] 在目标游戏中确认游戏自己的震动调用能驱动对应 Joy-Con。
