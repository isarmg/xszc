# Android：安装、构建与排障

当前源码候选版本 **1.1.5**，仅支持最新稳定系统系列 Android 17/API 37 及以上的 arm64-v8a 设备。Windows、Linux 和 macOS 在本页仅作为 Android 开发机，xszc 没有对应的桌面应用。当前候选尚未发布，下文下载链接指向已公开的 1.1.4；候选改动需同版源码构建。

安装后按[第一次备份](../getting-started.md)登录和验证。两端共享的备份设置、任务操作和数据维护分别见[配置](../configuration.md)、[日常使用](../usage.md)及[维护应用数据](../operations.md)。

## 安装、更新与卸载

### 安装正式 APK

从 [v1.1.4 发行页](https://github.com/isarmg/xszc/releases/tag/v1.1.4)下载 `xszc-android-1.1.4-arm64.apk` 和 `SHA256SUMS`。核对 APK 的 SHA-256 后，在手机打开文件，按系统提示为本次来源允许安装。

电脑计算哈希：Linux 使用 `sha256sum 文件名`，macOS 使用 `shasum -a 256 文件名`，Windows PowerShell 使用 `Get-FileHash 文件名 -Algorithm SHA256`。结果应与同版清单对应行一致。

已安装 Android platform-tools、手机开启 USB 调试并授权电脑时，可用以下命令安装。多设备连接时给每条 `adb` 加 `-s 实际序列号`。

```sh
adb devices
adb shell getprop ro.product.cpu.abilist
adb install -r ./xszc-android-1.1.4-arm64.apk
adb shell am start -n org.sarmg.xszc/.MainActivity
```

设备应显示 `device`，ABI 包含 `arm64-v8a`，安装返回 `Success`。`-r` 用同签名包覆盖安装并保留应用数据。

### 更新

使用同应用 ID、同签名的正式 APK 覆盖安装，再确认版本、原队列和一次新上传。签名不匹配时先核对安装来源和[正式签名身份](../android-signing.md)。卸载或“清除存储”会删除待传状态，不适合作为签名冲突的修复方式。

### 卸载

先关闭自动备份，完成或明确放弃待传任务，再退出账户。在系统应用信息页卸载，或执行：

```sh
adb uninstall org.sarmg.xszc
adb shell pm path org.sarmg.xszc
```

第二条正常无输出。卸载删除本机数据库、待传分块和 Android 私有凭据；系统照片原件和服务端已备份媒体保留。正式退役还需请管理员处理设备授权。

## 从源码构建

准备 Rust `1.99.0`，先在仓库根目录运行[公共检查](../development.md#rust-和公共检查)，再执行以下命令。

准备 JDK 17、Gradle `9.5.0`、Android SDK 37、Build Tools `37.0.0`、NDK `28.2.13676358` 和 platform-tools。SDK 平台包名为 `platforms;android-37.0`，模拟器镜像使用 `system-images;android-37.0;google_apis;x86_64`。设置 `ANDROID_HOME` 后，在 Linux/macOS shell 构建 arm64 Debug 应用：

```sh
rustup target add aarch64-linux-android
cargo install cargo-ndk --version 4.1.2 --locked
export ANDROID_NDK_ROOT="$ANDROID_HOME/ndk/28.2.13676358"
export ANDROID_NDK_HOME="$ANDROID_NDK_ROOT"
cargo ndk -t arm64-v8a -o clients/android/app/src/main/jniLibs   build -p xszc-mobile --release --locked
gradle -p clients/android testDebugUnitTest assembleDebug
adb install -r clients/android/app/build/outputs/apk/debug/app-debug.apk
adb shell am start -n org.sarmg.xszc/.MainActivity
```

Rust 输出 `libxszc_mobile.so`，Gradle 输出 `app-debug.apk`。连接设备并授权 USB 调试后安装；已存在不同签名的正式应用时，使用独立测试设备或模拟器，保留正式应用中的待传数据。

Windows PowerShell 使用同样的 JDK、Gradle、SDK 与 NDK 版本，先设置 SDK/NDK 的实际位置，再安装脚本所需的三个 Rust 目标：

```powershell
$env:ANDROID_HOME = "C:\path\to\Android\Sdk"
$env:ANDROID_NDK_ROOT = "$env:ANDROID_HOME\ndk\28.2.13676358"
$env:ANDROID_NDK_HOME = $env:ANDROID_NDK_ROOT
rustup target add aarch64-linux-android armv7-linux-androideabi x86_64-linux-android
cargo install cargo-ndk --version 4.1.2 --locked
./scripts/build-android-rust.ps1
gradle -p clients/android testDebugUnitTest assembleDebug
```

真实 JVM/JNI 检查运行 `./scripts/test-mobile-ffi-jni.sh`。准备 Android 模拟器后执行 `./scripts/test-android-emulator.sh`，覆盖权限、图库、上传和重开等原生路径。正式 APK 仅打包 arm64，签名配置见[Android 正式签名](../android-signing.md)。

## 平台排障

- APK 覆盖失败：核对 Android 版本、arm64 ABI、应用 ID 和签名，使用同签名正式包；成功后原队列应保留。
- 后台备份：检查 WorkManager 调度、照片授权和[备份条件](../configuration.md)，系统允许运行时才会触发自动任务。

### 日志

电脑连接并获得 USB 调试授权后：

```sh
adb shell dumpsys package org.sarmg.xszc
adb shell pidof org.sarmg.xszc
adb logcat --pid=ACTUAL_PID
```

第一条查看版本与权限，第二条取得当前进程 ID。将 `ACTUAL_PID` 换成非空的实际值；没有进程时先打开应用。Ctrl+C 结束日志。正常停用使用应用“退出”；`adb shell am force-stop org.sarmg.xszc` 是应急终止，后台任务可能要等用户再次打开才恢复。

登录、上传、重试和数据保留问题统一见[排查问题](../troubleshooting.md)。发布前按[发布验证](../development.md#发布验证)核对版本和实际设备结果。

[选择其他平台](../README.md#选择平台)
