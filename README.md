# Joy-Con Bridge

面向 Android 12 及以上设备的本地 Joy-Con 输入桥。应用通过
Shizuku 启动 shell UID 2000 的 UserService，从 `/dev/input/event*` 动态寻找 Nintendo
Joy-Con，再通过 `/dev/uinput` 创建支持 `FF_RUMBLE` 的标准虚拟手柄。无需 root。

## 三种模式

- **兼容双手柄**：抓取左右 Joy-Con，输出两个标准虚拟手柄。左右横握时分别旋转
  摇杆；左侧方向键和右侧 ABXY 按物理位置映射为标准 ABXY；SL/SR 映射为 L/R。
- **组合手柄**：抓取左右 Joy-Con，输出一个完整虚拟手柄。包含双摇杆、十字键、
  ABXY、L/R/ZL/ZR、+/−、摇杆按键、Home/Capture。
- **原生双手柄**：关闭所有虚拟设备并释放物理输入，让 Android/游戏直接读取左右
  Joy-Con。

组合手柄模式提供独立的 **AB 互换**、**XY 互换**开关。兼容双手柄模式则为左、右
横握 Joy-Con 各提供一组独立的 **AB 互换**和 **XY 互换**开关。所有设置都会自动
保存；开关变化后无需重建虚拟设备或重新接管 Joy-Con，约 200 ms 内更新当前虚拟
按键状态。

模式切换会销毁旧的 uinput 设备，然后按新拓扑重新注册。
部分游戏需要回到手柄设置页重新扫描，或者在启动游戏前先选择模式。

组合与兼容模式的虚拟手柄都向 Android 声明 `FF_RUMBLE`。游戏提交的强马达、弱马达
和持续时间会由 Bridge 接收并转换为 Nintendo HD Rumble 蓝牙输出报告。组合模式把强、
弱通道分别优先路由到左、右 Joy-Con，单通道效果会自动补到另一侧；兼容模式中每个
虚拟手柄只驱动对应的物理 Joy-Con。原生模式受设备内核的 `CONFIG_NINTENDO_FF`
配置限制。

## 设备识别与安全释放

应用不会保存 `event11`、`event13` 之类的编号。每次启动、断线或重连都会遍历
`/dev/input/event0..255`，读取 `EVIOCGID` 和能力位：

- 左 Joy-Con：VID `057e`、PID `2006`，且具备左摇杆和十字键能力；
- 右 Joy-Con：VID `057e`、PID `2007`，且具备右摇杆和 ABXY 能力；
- 同 VID/PID 但名称含 `IMU` 的传感器节点不会被抓取。

虚拟模式通过 `EVIOCGRAB` 独占两个主输入节点，避免游戏同时收到物理和虚拟输入。
切到原生模式、停止服务、Shizuku 退出或进程异常结束时，文件描述符关闭，内核会释放
grab；uinput 文件描述符关闭后内核也会注销虚拟设备。

## 使用

1. 在 Android 的蓝牙设置里先配对左右 Joy-Con。
2. 启动 Shizuku。
3. 安装并打开 APK，点击任一模式，首次使用时批准 Shizuku 权限。
4. 等待界面显示左右 Joy-Con 均已找到。
5. 切到游戏；若游戏缓存了设备列表，请重新扫描手柄。

## 构建

要求：JDK 17+、Android SDK 36、Build Tools 36.0.0、NDK 21.4.7075529。

```powershell
$env:JAVA_HOME = 'C:\Program Files\Android\Android Studio\jbr'
$env:ANDROID_HOME = "$env:LOCALAPPDATA\Android\Sdk"
.\tools\gradle-9.5.0\bin\gradle.bat --no-daemon `
  :app:testDebugUnitTest :app:lintDebug :app:assembleDebug
```

APK 输出：`app/build/outputs/apk/debug/app-debug.apk`。

应用界面使用 Material 3，支持简体中文、English 和日本語。默认跟随系统，系统语言
不受支持时回退英语，右上角可手动切换。原始服务状态与运行日志默认隐藏，可在页面
底部打开“开发者模式”查看；开发者日志固定使用英语。底部“关于”区域显示版本号并
链接到项目源码。

Gradle 的 `compileJoyConNative` 任务直接调用 NDK Clang 生成 arm64-v8a JNI 库，绕过旧
NDK 在带空格 Windows 路径下的 CMake/Ninja 路径问题。

## 已验证目标

- Lenovo TB323FU，Android 16 / API 36；
- `/dev/uinput` 可由 Shizuku shell UID 打开，内核启用 `CONFIG_INPUT_UINPUT`；
- 左主输入：`057e:2006`，ABS_X/ABS_Y；
- 右主输入：`057e:2007`，ABS_RX/ABS_RY；
- 左右各有一个 IMU 节点，能力筛选能将其排除；
- Debug APK 可安装并启动，special-use 前台服务正常运行；
- 虚拟设备被 Android 标记为 `GAMEPAD | JOYSTICK | VIBRATOR`；
- Android 输入设备震动 API 可产生 `FF_RUMBLE` 上传、启动和停止事件；
- Java/JNI 编译、映射单测、Android Lint、APK v2 签名检查均通过。

Shizuku 授权后的实际 `EVIOCGRAB` 与游戏内手柄扫描仍需在设备上由用户批准权限后
完成验收。详细记录见 [docs/VERIFICATION.md](docs/VERIFICATION.md)。

## 技术依据

- [Shizuku UserService API](https://github.com/RikkaApps/Shizuku-API)
- [Linux uinput userspace API](https://www.kernel.org/doc/html/latest/input/uinput.html)
- [Linux `hid-nintendo` Joy-Con 映射](https://github.com/torvalds/linux/blob/master/drivers/hid/hid-nintendo.c)

这是非官方互操作工具，不包含 Nintendo 固件、密钥、ROM 或商标素材。
