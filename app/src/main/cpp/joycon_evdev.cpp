#include <jni.h>

#include <android/log.h>
#include <errno.h>
#include <fcntl.h>
#include <linux/input.h>
#include <linux/uinput.h>
#include <poll.h>
#include <sys/ioctl.h>
#include <unistd.h>

#include <array>
#include <algorithm>
#include <cstdint>
#include <cstring>
#include <memory>
#include <mutex>
#include <sstream>
#include <string>

namespace {

constexpr const char *kLogTag = "JoyConBridgeEvdev";
constexpr int kNintendoVendor = 0x057e;
constexpr int kJoyConLeftProduct = 0x2006;
constexpr int kJoyConRightProduct = 0x2007;
constexpr int kLeft = 1;
constexpr int kRight = 2;
constexpr size_t kVirtualReportLength = 10;
constexpr int kMaxRumbleEffects = 16;

struct Device {
    int fd = -1;
    int side = 0;
    std::string path;
    std::string name;
    std::string unique;
    input_id id{};
};

struct Handle {
    std::array<Device, 2> devices;
    bool grabbed = false;
};

struct VirtualGamepad {
    int fd = -1;
    bool created = false;
    std::mutex writeMutex;
    std::array<ff_effect, kMaxRumbleEffects> effects{};
    std::array<bool, kMaxRumbleEffects> effectValid{};
};

std::mutex gErrorMutex;
std::string gLastError;

void setLastError(const std::string &message) {
    std::lock_guard<std::mutex> guard(gErrorMutex);
    gLastError = message;
    __android_log_print(ANDROID_LOG_WARN, kLogTag, "%s", message.c_str());
}

std::string lastError() {
    std::lock_guard<std::mutex> guard(gErrorMutex);
    return gLastError;
}

std::string errnoMessage(const std::string &prefix) {
    return prefix + ": " + std::strerror(errno);
}

template <size_t N>
bool testBit(const std::array<unsigned long, N> &bits, int bit) {
    constexpr int kBitsPerLong = static_cast<int>(sizeof(unsigned long) * 8U);
    const int index = bit / kBitsPerLong;
    const int offset = bit % kBitsPerLong;
    return index >= 0 && static_cast<size_t>(index) < bits.size()
            && (bits[static_cast<size_t>(index)] & (1UL << offset)) != 0;
}

bool isGamepadNode(int fd, int side, const std::string &name) {
    if (name.find("IMU") != std::string::npos) {
        return false;
    }

    std::array<unsigned long, (KEY_MAX / (sizeof(unsigned long) * 8U)) + 2U> keys{};
    std::array<unsigned long, (ABS_MAX / (sizeof(unsigned long) * 8U)) + 2U> axes{};
    if (ioctl(fd, EVIOCGBIT(EV_KEY, sizeof(keys)), keys.data()) < 0
            || ioctl(fd, EVIOCGBIT(EV_ABS, sizeof(axes)), axes.data()) < 0) {
        return false;
    }

    const bool hasButtons = side == kLeft
            ? testBit(keys, BTN_DPAD_UP) && testBit(keys, BTN_TL)
            : testBit(keys, BTN_SOUTH) && testBit(keys, BTN_TR);
    const bool hasStick = side == kLeft
            ? testBit(axes, ABS_X) && testBit(axes, ABS_Y)
            : testBit(axes, ABS_RX) && testBit(axes, ABS_RY);
    return hasButtons && hasStick;
}

bool supportsRumble(int fd) {
    std::array<unsigned long, (EV_MAX / (sizeof(unsigned long) * 8U)) + 2U> events{};
    if (ioctl(fd, EVIOCGBIT(0, sizeof(events)), events.data()) < 0
            || !testBit(events, EV_FF)) {
        return false;
    }

    std::array<unsigned long, (FF_MAX / (sizeof(unsigned long) * 8U)) + 2U> effects{};
    return ioctl(fd, EVIOCGBIT(EV_FF, sizeof(effects)), effects.data()) >= 0
            && testBit(effects, FF_RUMBLE);
}

std::unique_ptr<Handle> scanJoyCons(bool grab) {
    auto handle = std::make_unique<Handle>();
    handle->grabbed = grab;
    bool sawPermissionError = false;

    for (int eventNumber = 0; eventNumber < 256; ++eventNumber) {
        std::string path = "/dev/input/event" + std::to_string(eventNumber);
        int fd = open(path.c_str(), O_RDONLY | O_NONBLOCK | O_CLOEXEC);
        if (fd < 0) {
            if (errno == EACCES || errno == EPERM) {
                sawPermissionError = true;
            }
            continue;
        }

        input_id id{};
        if (ioctl(fd, EVIOCGID, &id) < 0 || id.vendor != kNintendoVendor) {
            close(fd);
            continue;
        }

        int side = 0;
        if (id.product == kJoyConLeftProduct) {
            side = kLeft;
        } else if (id.product == kJoyConRightProduct) {
            side = kRight;
        } else {
            close(fd);
            continue;
        }

        std::array<char, 256> nameBuffer{};
        if (ioctl(fd, EVIOCGNAME(nameBuffer.size()), nameBuffer.data()) < 0) {
            nameBuffer[0] = '\0';
        }
        std::string name(nameBuffer.data());
        if (!isGamepadNode(fd, side, name)) {
            close(fd);
            continue;
        }

        Device &slot = handle->devices[static_cast<size_t>(side - 1)];
        if (slot.fd >= 0) {
            close(fd);
            continue;
        }

        if (grab && ioctl(fd, EVIOCGRAB, 1) < 0) {
            std::string error = errnoMessage("EVIOCGRAB " + path);
            close(fd);
            for (Device &device : handle->devices) {
                if (device.fd >= 0) {
                    ioctl(device.fd, EVIOCGRAB, 0);
                    close(device.fd);
                    device.fd = -1;
                }
            }
            setLastError(error);
            return nullptr;
        }

        slot.fd = fd;
        slot.side = side;
        slot.path = path;
        slot.name = name;
        std::array<char, 64> uniqueBuffer{};
        if (ioctl(fd, EVIOCGUNIQ(uniqueBuffer.size()), uniqueBuffer.data()) >= 0) {
            slot.unique = uniqueBuffer.data();
        }
        slot.id = id;
    }

    if (handle->devices[0].fd < 0 && handle->devices[1].fd < 0) {
        setLastError(sawPermissionError
                ? "shell cannot read /dev/input/event*"
                : "No Joy-Con game input nodes found for VID 057e / PID 2006 or 2007");
        return nullptr;
    }
    setLastError("");
    return handle;
}

int deviceMask(const Handle *handle) {
    if (handle == nullptr) {
        return 0;
    }
    int mask = 0;
    if (handle->devices[0].fd >= 0) {
        mask |= kLeft;
    }
    if (handle->devices[1].fd >= 0) {
        mask |= kRight;
    }
    return mask;
}

std::string describe(const Handle *handle) {
    if (handle == nullptr) {
        return lastError();
    }
    std::ostringstream result;
    for (const Device &device : handle->devices) {
        if (device.fd < 0) {
            continue;
        }
        if (result.tellp() > 0) {
            result << "; ";
        }
        result << (device.side == kLeft ? "L=" : "R=") << device.path
               << " (" << std::hex << device.id.vendor << ':' << device.id.product
               << std::dec << ' ' << device.name
               << "; rumble=" << (supportsRumble(device.fd) ? "FF_RUMBLE" : "unavailable")
               << ')';
    }
    return result.str();
}

std::string uniqueIds(const Handle *handle) {
    if (handle == nullptr) {
        return lastError();
    }
    std::ostringstream result;
    for (const Device &device : handle->devices) {
        if (device.fd < 0 || device.unique.empty()) {
            continue;
        }
        if (result.tellp() > 0) {
            result << ';';
        }
        result << (device.side == kLeft ? "L=" : "R=") << device.unique;
    }
    return result.str();
}

void closeHandle(Handle *handle) {
    if (handle == nullptr) {
        return;
    }
    for (Device &device : handle->devices) {
        if (device.fd < 0) {
            continue;
        }
        if (handle->grabbed) {
            ioctl(device.fd, EVIOCGRAB, 0);
        }
        close(device.fd);
        device.fd = -1;
    }
    delete handle;
}

jstring toJavaString(JNIEnv *env, const std::string &value) {
    return env->NewStringUTF(value.c_str());
}

Handle *fromHandle(jlong value) {
    return reinterpret_cast<Handle *>(static_cast<intptr_t>(value));
}

VirtualGamepad *fromVirtualHandle(jlong value) {
    return reinterpret_cast<VirtualGamepad *>(static_cast<intptr_t>(value));
}

bool setCapability(int fd, unsigned long request, int code, const char *label) {
    if (ioctl(fd, request, code) >= 0) {
        return true;
    }
    setLastError(errnoMessage(std::string("uinput ") + label));
    return false;
}

bool setAbsoluteAxis(int fd, int code, int minimum, int maximum, int flat) {
    uinput_abs_setup setup{};
    setup.code = static_cast<__u16>(code);
    setup.absinfo.minimum = minimum;
    setup.absinfo.maximum = maximum;
    setup.absinfo.flat = flat;
    setup.absinfo.value = minimum == 0 && maximum == 255 ? 128 : 0;
    if (code == ABS_BRAKE || code == ABS_GAS) {
        setup.absinfo.value = 0;
    }
    if (ioctl(fd, UI_ABS_SETUP, &setup) >= 0) {
        return true;
    }
    setLastError(errnoMessage("uinput UI_ABS_SETUP"));
    return false;
}

std::unique_ptr<VirtualGamepad> createVirtualGamepad(
        const std::string &name,
        int vendor,
        int product) {
    auto gamepad = std::make_unique<VirtualGamepad>();
    gamepad->fd = open("/dev/uinput", O_RDWR | O_NONBLOCK | O_CLOEXEC);
    if (gamepad->fd < 0) {
        setLastError(errnoMessage("open /dev/uinput"));
        return nullptr;
    }

    const std::array<int, 15> keys = {
            BTN_SOUTH, BTN_EAST, BTN_C, BTN_NORTH, BTN_WEST, BTN_Z,
            BTN_TL, BTN_TR, BTN_TL2, BTN_TR2, BTN_SELECT, BTN_START,
            BTN_MODE, BTN_THUMBL, BTN_THUMBR
    };
    const std::array<int, 8> axes = {
            ABS_X, ABS_Y, ABS_Z, ABS_RZ,
            ABS_BRAKE, ABS_GAS, ABS_HAT0X, ABS_HAT0Y
    };

    if (!setCapability(gamepad->fd, UI_SET_EVBIT, EV_KEY, "EV_KEY")
            || !setCapability(gamepad->fd, UI_SET_EVBIT, EV_ABS, "EV_ABS")
            || !setCapability(gamepad->fd, UI_SET_EVBIT, EV_FF, "EV_FF")
            || !setCapability(gamepad->fd, UI_SET_FFBIT, FF_RUMBLE, "FF_RUMBLE")) {
        close(gamepad->fd);
        return nullptr;
    }
    for (int key : keys) {
        if (!setCapability(gamepad->fd, UI_SET_KEYBIT, key, "key")) {
            close(gamepad->fd);
            return nullptr;
        }
    }
    for (int axis : axes) {
        if (!setCapability(gamepad->fd, UI_SET_ABSBIT, axis, "axis")) {
            close(gamepad->fd);
            return nullptr;
        }
    }
    if (!setAbsoluteAxis(gamepad->fd, ABS_X, 0, 255, 15)
            || !setAbsoluteAxis(gamepad->fd, ABS_Y, 0, 255, 15)
            || !setAbsoluteAxis(gamepad->fd, ABS_Z, 0, 255, 15)
            || !setAbsoluteAxis(gamepad->fd, ABS_RZ, 0, 255, 15)
            || !setAbsoluteAxis(gamepad->fd, ABS_BRAKE, 0, 255, 15)
            || !setAbsoluteAxis(gamepad->fd, ABS_GAS, 0, 255, 15)
            || !setAbsoluteAxis(gamepad->fd, ABS_HAT0X, -1, 1, 0)
            || !setAbsoluteAxis(gamepad->fd, ABS_HAT0Y, -1, 1, 0)) {
        close(gamepad->fd);
        return nullptr;
    }

    uinput_setup setup{};
    setup.id.bustype = BUS_BLUETOOTH;
    setup.id.vendor = static_cast<__u16>(vendor);
    setup.id.product = static_cast<__u16>(product);
    setup.id.version = 1;
    setup.ff_effects_max = kMaxRumbleEffects;
    std::strncpy(setup.name, name.c_str(), sizeof(setup.name) - 1);
    if (ioctl(gamepad->fd, UI_DEV_SETUP, &setup) < 0
            || ioctl(gamepad->fd, UI_DEV_CREATE) < 0) {
        setLastError(errnoMessage("create uinput gamepad"));
        close(gamepad->fd);
        return nullptr;
    }
    gamepad->created = true;
    setLastError("");
    return gamepad;
}

void closeVirtualGamepad(VirtualGamepad *gamepad) {
    if (gamepad == nullptr) {
        return;
    }
    if (gamepad->fd >= 0) {
        if (gamepad->created) {
            ioctl(gamepad->fd, UI_DEV_DESTROY);
        }
        close(gamepad->fd);
        gamepad->fd = -1;
    }
    delete gamepad;
}

void appendEvent(std::array<input_event, 24> &events, size_t &count,
                 __u16 type, __u16 code, __s32 value) {
    input_event &event = events[count++];
    event.type = type;
    event.code = code;
    event.value = value;
}

bool writeVirtualReport(VirtualGamepad *gamepad, const std::array<uint8_t, 10> &report) {
    if (gamepad == nullptr || gamepad->fd < 0 || report[0] != 1) {
        setLastError("Invalid uinput gamepad or report");
        return false;
    }

    static constexpr std::array<int, 15> kButtonCodes = {
            BTN_SOUTH, BTN_EAST, BTN_C, BTN_NORTH, BTN_WEST, BTN_Z,
            BTN_TL, BTN_TR, BTN_TL2, BTN_TR2, BTN_SELECT, BTN_START,
            BTN_MODE, BTN_THUMBL, BTN_THUMBR
    };
    static constexpr std::array<int, 9> kHatX = {0, 1, 1, 1, 0, -1, -1, -1, 0};
    static constexpr std::array<int, 9> kHatY = {-1, -1, 0, 1, 1, 1, 0, -1, 0};

    std::array<input_event, 24> events{};
    size_t count = 0;
    const int buttons = report[1] | (static_cast<int>(report[2]) << 8);
    for (size_t index = 0; index < kButtonCodes.size(); ++index) {
        appendEvent(events, count, EV_KEY, static_cast<__u16>(kButtonCodes[index]),
                    (buttons & (1 << index)) != 0 ? 1 : 0);
    }
    const int hat = std::min<int>(report[3], 8);
    appendEvent(events, count, EV_ABS, ABS_HAT0X, kHatX[static_cast<size_t>(hat)]);
    appendEvent(events, count, EV_ABS, ABS_HAT0Y, kHatY[static_cast<size_t>(hat)]);
    appendEvent(events, count, EV_ABS, ABS_X, report[4]);
    appendEvent(events, count, EV_ABS, ABS_Y, report[5]);
    appendEvent(events, count, EV_ABS, ABS_Z, report[6]);
    appendEvent(events, count, EV_ABS, ABS_RZ, report[7]);
    appendEvent(events, count, EV_ABS, ABS_BRAKE, report[8]);
    appendEvent(events, count, EV_ABS, ABS_GAS, report[9]);
    appendEvent(events, count, EV_SYN, SYN_REPORT, 0);

    std::lock_guard<std::mutex> guard(gamepad->writeMutex);
    const size_t byteCount = count * sizeof(input_event);
    const ssize_t written = write(gamepad->fd, events.data(), byteCount);
    if (written == static_cast<ssize_t>(byteCount)) {
        return true;
    }
    setLastError(written < 0
            ? errnoMessage("write uinput report")
            : "Short write to /dev/uinput");
    return false;
}

int readRumbleEvent(VirtualGamepad *gamepad, std::array<jint, 4> &output, int timeoutMs) {
    if (gamepad == nullptr || gamepad->fd < 0) {
        return -1;
    }
    pollfd pollFd{gamepad->fd, POLLIN, 0};
    int pollResult;
    do {
        pollResult = poll(&pollFd, 1, timeoutMs);
    } while (pollResult < 0 && errno == EINTR);
    if (pollResult == 0) {
        return 0;
    }
    if (pollResult < 0 || (pollFd.revents & (POLLERR | POLLHUP | POLLNVAL)) != 0) {
        setLastError(pollResult < 0 ? errnoMessage("poll uinput") : "uinput device closed");
        return -1;
    }

    while (true) {
        input_event event{};
        const ssize_t bytes = read(gamepad->fd, &event, sizeof(event));
        if (bytes < 0 && (errno == EAGAIN || errno == EINTR)) {
            return 0;
        }
        if (bytes != static_cast<ssize_t>(sizeof(event))) {
            setLastError(bytes < 0 ? errnoMessage("read uinput") : "Short read from /dev/uinput");
            return -1;
        }

        if (event.type == EV_UINPUT && event.code == UI_FF_UPLOAD) {
            uinput_ff_upload upload{};
            upload.request_id = static_cast<__u32>(event.value);
            if (ioctl(gamepad->fd, UI_BEGIN_FF_UPLOAD, &upload) < 0) {
                setLastError(errnoMessage("UI_BEGIN_FF_UPLOAD"));
                continue;
            }
            const int effectId = upload.effect.id;
            if (upload.effect.type == FF_RUMBLE
                    && effectId >= 0 && effectId < kMaxRumbleEffects) {
                gamepad->effects[static_cast<size_t>(effectId)] = upload.effect;
                gamepad->effectValid[static_cast<size_t>(effectId)] = true;
                upload.retval = 0;
            } else {
                upload.retval = -EINVAL;
            }
            if (ioctl(gamepad->fd, UI_END_FF_UPLOAD, &upload) < 0) {
                setLastError(errnoMessage("UI_END_FF_UPLOAD"));
            }
            continue;
        }

        if (event.type == EV_UINPUT && event.code == UI_FF_ERASE) {
            uinput_ff_erase erase{};
            erase.request_id = static_cast<__u32>(event.value);
            if (ioctl(gamepad->fd, UI_BEGIN_FF_ERASE, &erase) < 0) {
                setLastError(errnoMessage("UI_BEGIN_FF_ERASE"));
                continue;
            }
            if (erase.effect_id < kMaxRumbleEffects) {
                gamepad->effectValid[erase.effect_id] = false;
            }
            erase.retval = 0;
            if (ioctl(gamepad->fd, UI_END_FF_ERASE, &erase) < 0) {
                setLastError(errnoMessage("UI_END_FF_ERASE"));
            }
            continue;
        }

        if (event.type != EV_FF || event.code == FF_GAIN || event.code == FF_AUTOCENTER) {
            continue;
        }
        const int effectId = event.code;
        output[0] = effectId;
        if (event.value == 0) {
            output[1] = 0;
            output[2] = 0;
            output[3] = 0;
            return 1;
        }
        if (effectId < 0 || effectId >= kMaxRumbleEffects
                || !gamepad->effectValid[static_cast<size_t>(effectId)]) {
            continue;
        }
        const ff_effect &effect = gamepad->effects[static_cast<size_t>(effectId)];
        output[1] = effect.u.rumble.strong_magnitude;
        output[2] = effect.u.rumble.weak_magnitude;
        output[3] = effect.replay.length;
        return 1;
    }
}

}  // namespace

extern "C" JNIEXPORT jlong JNICALL
Java_dev_joycon2_bridge_output_ShizukuInputService_nativeOpenJoyCons(
        JNIEnv *, jclass, jboolean grab) {
    std::unique_ptr<Handle> handle = scanJoyCons(grab == JNI_TRUE);
    return static_cast<jlong>(reinterpret_cast<intptr_t>(handle.release()));
}

extern "C" JNIEXPORT void JNICALL
Java_dev_joycon2_bridge_output_ShizukuInputService_nativeCloseJoyCons(
        JNIEnv *, jclass, jlong value) {
    closeHandle(fromHandle(value));
}

extern "C" JNIEXPORT jint JNICALL
Java_dev_joycon2_bridge_output_ShizukuInputService_nativeDeviceMask(
        JNIEnv *, jclass, jlong value) {
    return deviceMask(fromHandle(value));
}

extern "C" JNIEXPORT jstring JNICALL
Java_dev_joycon2_bridge_output_ShizukuInputService_nativeDescribe(
        JNIEnv *env, jclass, jlong value) {
    return toJavaString(env, describe(fromHandle(value)));
}

extern "C" JNIEXPORT jstring JNICALL
Java_dev_joycon2_bridge_output_ShizukuInputService_nativeUniqueIds(
        JNIEnv *env, jclass, jlong value) {
    return toJavaString(env, uniqueIds(fromHandle(value)));
}

extern "C" JNIEXPORT jstring JNICALL
Java_dev_joycon2_bridge_output_ShizukuInputService_nativeLastError(
        JNIEnv *env, jclass) {
    return toJavaString(env, lastError());
}

extern "C" JNIEXPORT jstring JNICALL
Java_dev_joycon2_bridge_output_ShizukuInputService_nativeScanSummary(
        JNIEnv *env, jclass) {
    std::unique_ptr<Handle> handle = scanJoyCons(false);
    return toJavaString(env, handle == nullptr ? lastError() : describe(handle.get()));
}

extern "C" JNIEXPORT jint JNICALL
Java_dev_joycon2_bridge_output_ShizukuInputService_nativeScanMask(
        JNIEnv *, jclass) {
    std::unique_ptr<Handle> handle = scanJoyCons(false);
    return deviceMask(handle.get());
}

extern "C" JNIEXPORT jstring JNICALL
Java_dev_joycon2_bridge_output_ShizukuInputService_nativeScanUniqueIds(
        JNIEnv *env, jclass) {
    std::unique_ptr<Handle> handle = scanJoyCons(false);
    return toJavaString(env, uniqueIds(handle.get()));
}

extern "C" JNIEXPORT jint JNICALL
Java_dev_joycon2_bridge_output_ShizukuInputService_nativeReadEvent(
        JNIEnv *env, jclass, jlong value, jintArray output, jint timeoutMs) {
    Handle *handle = fromHandle(value);
    if (handle == nullptr || output == nullptr || env->GetArrayLength(output) < 4) {
        return -1;
    }

    std::array<pollfd, 2> pollFds{};
    std::array<int, 2> sides{};
    int count = 0;
    for (const Device &device : handle->devices) {
        if (device.fd < 0) {
            continue;
        }
        pollFds[static_cast<size_t>(count)] = {device.fd, POLLIN, 0};
        sides[static_cast<size_t>(count)] = device.side;
        ++count;
    }
    if (count == 0) {
        return -2;
    }

    int result;
    do {
        result = poll(pollFds.data(), static_cast<nfds_t>(count), timeoutMs);
    } while (result < 0 && errno == EINTR);
    if (result == 0) {
        return 0;
    }
    if (result < 0) {
        setLastError(errnoMessage("poll Joy-Con"));
        return -2;
    }

    for (int index = 0; index < count; ++index) {
        const pollfd &pollFd = pollFds[static_cast<size_t>(index)];
        if ((pollFd.revents & (POLLERR | POLLHUP | POLLNVAL)) != 0) {
            setLastError("Joy-Con disconnected; rescanning");
            return -2;
        }
        if ((pollFd.revents & POLLIN) == 0) {
            continue;
        }

        input_event event{};
        const ssize_t bytes = read(pollFd.fd, &event, sizeof(event));
        if (bytes == static_cast<ssize_t>(sizeof(event))) {
            const jint packet[4] = {
                    sides[static_cast<size_t>(index)],
                    static_cast<jint>(event.type),
                    static_cast<jint>(event.code),
                    static_cast<jint>(event.value)
            };
            env->SetIntArrayRegion(output, 0, 4, packet);
            return 1;
        }
        if (bytes < 0 && (errno == EAGAIN || errno == EINTR)) {
            continue;
        }
        setLastError(bytes == 0
                ? "Joy-Con input node closed"
                : errnoMessage("read Joy-Con"));
        return -2;
    }
    return 0;
}

extern "C" JNIEXPORT jint JNICALL
Java_dev_joycon2_bridge_output_ShizukuInputService_nativeGetAbsInfo(
        JNIEnv *env, jclass, jlong value, jint side, jint code, jintArray output) {
    Handle *handle = fromHandle(value);
    if (handle == nullptr || side < kLeft || side > kRight || output == nullptr
            || env->GetArrayLength(output) < 4) {
        return 0;
    }
    const Device &device = handle->devices[static_cast<size_t>(side - 1)];
    if (device.fd < 0) {
        return 0;
    }
    input_absinfo info{};
    if (ioctl(device.fd, EVIOCGABS(code), &info) < 0) {
        return 0;
    }
    const jint values[4] = {info.value, info.minimum, info.maximum, info.flat};
    env->SetIntArrayRegion(output, 0, 4, values);
    return 1;
}

extern "C" JNIEXPORT jint JNICALL
Java_dev_joycon2_bridge_output_ShizukuInputService_nativeGetKeyState(
        JNIEnv *, jclass, jlong value, jint side, jint code) {
    Handle *handle = fromHandle(value);
    if (handle == nullptr || side < kLeft || side > kRight || code < 0 || code > KEY_MAX) {
        return 0;
    }
    const Device &device = handle->devices[static_cast<size_t>(side - 1)];
    if (device.fd < 0) {
        return 0;
    }
    std::array<unsigned long, (KEY_MAX / (sizeof(unsigned long) * 8U)) + 2U> keys{};
    if (ioctl(device.fd, EVIOCGKEY(sizeof(keys)), keys.data()) < 0) {
        return 0;
    }
    return testBit(keys, code) ? 1 : 0;
}

extern "C" JNIEXPORT jstring JNICALL
Java_dev_joycon2_bridge_output_ShizukuInputService_nativeUinputProbe(
        JNIEnv *env, jclass) {
    const int fd = open("/dev/uinput", O_RDWR | O_NONBLOCK | O_CLOEXEC);
    if (fd < 0) {
        return toJavaString(env, "Error: " + errnoMessage("shell cannot open /dev/uinput"));
    }
    unsigned int version = 0;
    const bool hasVersion = ioctl(fd, UI_GET_VERSION, &version) >= 0;
    close(fd);
    std::ostringstream result;
    result << "uinput=/dev/uinput";
    if (hasVersion) {
        result << "; version=" << version;
    }
    return toJavaString(env, result.str());
}

extern "C" JNIEXPORT jlong JNICALL
Java_dev_joycon2_bridge_output_ShizukuInputService_nativeCreateVirtualGamepad(
        JNIEnv *env, jclass, jstring name, jint vendor, jint product) {
    if (name == nullptr) {
        setLastError("Virtual gamepad name is missing");
        return 0;
    }
    const char *characters = env->GetStringUTFChars(name, nullptr);
    if (characters == nullptr) {
        setLastError("Could not read virtual gamepad name");
        return 0;
    }
    const std::string deviceName(characters);
    env->ReleaseStringUTFChars(name, characters);
    std::unique_ptr<VirtualGamepad> gamepad = createVirtualGamepad(
            deviceName,
            vendor,
            product);
    return static_cast<jlong>(reinterpret_cast<intptr_t>(gamepad.release()));
}

extern "C" JNIEXPORT void JNICALL
Java_dev_joycon2_bridge_output_ShizukuInputService_nativeDestroyVirtualGamepad(
        JNIEnv *, jclass, jlong value) {
    closeVirtualGamepad(fromVirtualHandle(value));
}

extern "C" JNIEXPORT jboolean JNICALL
Java_dev_joycon2_bridge_output_ShizukuInputService_nativeWriteVirtualGamepad(
        JNIEnv *env, jclass, jlong value, jbyteArray input) {
    if (input == nullptr || env->GetArrayLength(input) != kVirtualReportLength) {
        setLastError("Virtual gamepad report must contain 10 bytes");
        return JNI_FALSE;
    }
    std::array<uint8_t, kVirtualReportLength> report{};
    env->GetByteArrayRegion(
            input,
            0,
            static_cast<jsize>(report.size()),
            reinterpret_cast<jbyte *>(report.data()));
    return writeVirtualReport(fromVirtualHandle(value), report) ? JNI_TRUE : JNI_FALSE;
}

extern "C" JNIEXPORT jint JNICALL
Java_dev_joycon2_bridge_output_ShizukuInputService_nativeReadRumble(
        JNIEnv *env, jclass, jlong value, jintArray output, jint timeoutMs) {
    if (output == nullptr || env->GetArrayLength(output) < 4) {
        return -1;
    }
    std::array<jint, 4> result{};
    const int readResult = readRumbleEvent(
            fromVirtualHandle(value),
            result,
            std::max(0, static_cast<int>(timeoutMs)));
    if (readResult > 0) {
        env->SetIntArrayRegion(output, 0, static_cast<jsize>(result.size()), result.data());
    }
    return readResult;
}
