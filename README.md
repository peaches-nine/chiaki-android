# Chiaki Android

基于 [chiaki](https://git.sr.ht/~thestr4ng3r/chiaki) 修改的 Android 客户端，增加 PS5 DualSense 手柄全功能支持（通过 [DS5Dongle](https://github.com/awalol/DS5Dongle) USB 适配器）、FSR/NIS 超分、性能优化。

**Disclaimer:** This project is not endorsed or certified by Sony Interactive Entertainment LLC.

## 新增功能

- **DS5Dongle 集成** — 插入 DS5Dongle（Pico 2W USB 蓝牙适配器）后自动识别，支持 HD 震动、自适应扳机、触摸板、陀螺仪完整功能，拔掉后自动切回虚拟按键
- **FSR 1.0 / NIS 超分** — 设置中可选 FSR 1.0 或 NVIDIA Image Scaling，支持锐度调节
- **架构优化** — ControllerState 内存复用、JNI 事件回调线程驻留、RxJava 热路径替换为回调

## 构建

### 环境需求

- JDK 17+
- Android SDK 33+、NDK 25+
- CMake 3.22+、Python 3 + protobuf

### 编译

```bash
git clone https://github.com/peaches-nine/chiaki-android.git
cd chiaki-android

# 初始化缺失的子模块
git clone --depth 1 https://github.com/nanopb/nanopb.git third-party/nanopb
git clone --depth 1 https://github.com/tsuraan/Jerasure.git third-party/jerasure
git clone --depth 1 https://github.com/ceph/gf-complete.git third-party/gf-complete

# 设置 sdk/ndk 路径
cd android
echo "sdk.dir=$ANDROID_HOME" > local.properties
echo "ndk.dir=$ANDROID_HOME/ndk/25.2.9519653" >> local.properties

# 编译 APK
./gradlew assembleDebug
# 输出: android/app/build/outputs/apk/debug/app-debug.apk
```

## 使用 DS5Dongle

1. 将 DS5Dongle 固件刷入 Pico 2W
2. DualSense 手柄通过蓝牙配对到 DS5Dongle
3. DS5Dongle 通过 USB-OTG 连接 Android 手机
4. 打开 chiaki，首次插上会弹出 USB 权限对话框，**允许**
5. 连接 PS5，手柄震动和自适应扳机在支持的游戏里直接生效
6. 手柄扬声器和耳机孔通过 DS5Dongle 的 USB 音频自动路由

## 超分设置

设置 → 超分辨率 → 选择 FSR 1.0 或 NIS，调节锐度 0-100。关闭时走原生 SurfaceView，零额外开销。

## 许可

- 本项目基于 [chiaki](https://git.sr.ht/~thestr4ng3r/chiaki)，使用 AGPL-3.0-only-OpenSSL 许可
- FSR shader 来自 [Moonlight](https://github.com/moonlight-stream/moonlight-android)（GPL-3.0）
- NIS 为独立实现的 GLSL 版本
