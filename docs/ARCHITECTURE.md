# Architecture

## 数据流

```text
Joy-Con L/R Bluetooth HID
        │
        ▼
/dev/input/event* ── EVIOCGID / capability filter
        │
        ├── Native dual: close fds, Android reads physical devices
        │
        └── Virtual modes: EVIOCGRAB
                    │
                    ▼
             JNI evdev poll/read
                    │
                    ▼
             JoyConEvdevMapper
                ┌───┴────┐
                ▼        ▼
             combined   sideways L/R
                │        │
                └───┬────┘
                    ▼
             uinput input_event batch
                    │
                    ▼
          shell-owned `/dev/uinput`
                    │
                    ▼
        Android InputReader virtual gamepad(s)
                    │
                    ▼
      FF_RUMBLE upload/start/stop events
                    │
                    ▼
       Nintendo HD Rumble over Bluetooth HID
```

## 进程边界

- 普通应用进程：`MainActivity`、`BridgeService`、`ShizukuGamepadBackend`。负责 UI、
  前台服务、Shizuku 权限和 AIDL 状态轮询。
- shell UID 2000 进程：`ShizukuInputService`。加载 `libjoycon_evdev.so`，持有
  `EVIOCGRAB` 与 uinput fd，所有高权限资源生命周期都在这里闭合。
- JNI uinput 后端注册按键、摇杆、扳机、方向帽以及 `EV_FF/FF_RUMBLE`，负责处理
  `UI_FF_UPLOAD`、`UI_FF_ERASE` 和 `EV_FF`。

## 重连

虚拟模式的输入线程以 200 ms poll 周期读取事件。`POLLHUP`、`POLLERR`、短读或
`SYN_DROPPED` 会关闭当前 fd 并重新扫描。只找到一侧时每 1.5 秒重开一次，以便另一侧
连接后自动加入。会话 generation 防止快速重复切换同一模式时旧线程重新抢占设备。

## 横握映射

两只 Joy-Con 均以 rail 朝上：

- 左侧逆时针旋转：`X' = Y`、`Y' = -X`；方向键 Down/Left/Right/Up → A/B/X/Y。
- 右侧顺时针旋转：`X' = -Y`、`Y' = X`；X/A/Y/B → A/B/X/Y。
- SL/SR → L1/R1；外侧 L/ZL 或 R/ZR → L2/R2。

映射依据目标设备当前内核公开的 hid-nintendo evdev 键码和上游 Linux
`hid-nintendo.c`。
