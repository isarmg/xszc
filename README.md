# ana

ana 是 Android 和 iOS 媒体备份客户端。用账户登录自建 ana-server 后，可通过 HTTPS 备份获准访问的照片和视频。

## 项目功能

- 本地与云端图库、照片浏览和全屏视频播放。
- 在云端按时间线浏览、查看往年回忆，以及查找带 GPS 坐标的照片和视频。
- 同一服务端可以使用不同账号，切换账号时按账号隔离备份队列和云端缓存。
- 手动与自动备份、持久化上传队列、传输进度和原件下载。
- 按网络、电源、媒体类型及相册设置备份范围，由系统安全存储管理凭据。

## 适用平台

当前源码仅支持最新稳定系统系列：Android 17（API 37）及以上，正式 APK 仅支持 arm64-v8a；iOS 27.0 及以上。iOS 发行包未签名，安装前需要使用自己的 Apple 身份签名。当前源码候选版本为 1.0.0，尚未发布；本次重新建立 1.0.0 发行基线；下列包名用于同版源码构建或新发行完成后的资产，不代表旧包已经更新。

## 快速部署

1. 获取对应版本的 APK / IPA 和 `SHA256SUMS`，核对安装包 SHA-256。
2. Android 安装 `ana-android-1.0.0-arm64.apk`；iOS 为 `ana-ios-1.0.0-unsigned.ipa` 签名后，通过 Xcode、Apple Configurator 或受管理部署系统安装。
3. 请 ana-server 管理员开通备份账号。在应用中输入服务器 HTTPS 根地址（例如 `https://backup.example.com`）、账号和密码。
4. 在系统界面授权照片/视频访问，设置备份范围与网络、电源条件；先手动备份少量媒体，在“传输”和“云端”确认完成，再启用自动备份。

Android 开发机已配置 ADB、设备已授权 USB 调试时，也可安装并打开正式包：

```sh
adb install -r ./ana-android-1.0.0-arm64.apk
adb shell am start -n org.sarmg.ana/.MainActivity
```

覆盖安装须使用相同签名；不要为解决签名冲突卸载应用，卸载会删除本地待传状态。

## 编译部署

先克隆本仓库并进入根目录，准备 Rust `1.99.0`。

Android：准备 JDK 17、Gradle `9.5.0`、Android SDK 37 / Build Tools `37.0.0`、NDK `28.2.13676358` 和 platform-tools。SDK 平台包名为 `platforms;android-37.0`。设置 `ANDROID_HOME` 后在 Linux/macOS shell 执行：

```sh
rustup target add aarch64-linux-android
cargo install cargo-ndk --version 4.1.2 --locked
export ANDROID_NDK_ROOT="$ANDROID_HOME/ndk/28.2.13676358"
export ANDROID_NDK_HOME="$ANDROID_NDK_ROOT"
cargo ndk -t arm64-v8a -o clients/android/app/src/main/jniLibs build -p ana-mobile --release --locked
gradle -p clients/android testDebugUnitTest assembleDebug
adb install -r clients/android/app/build/outputs/apk/debug/app-debug.apk
adb shell am start -n org.sarmg.ana/.MainActivity
```

这是开发用 Debug APK；正式包需要配置自己的发行签名并完成平台验收。

iOS：在装有稳定版 Xcode 27.0、iOS 27.0 SDK 和 XcodeGen `2.46.0` 的 Mac 上执行：

```sh
./scripts/verify-ios-toolchain.sh
rustup target add aarch64-apple-ios aarch64-apple-ios-sim
./scripts/build-ios-rust.sh
(cd clients/ios && xcodegen generate)
open clients/ios/Ana.xcodeproj
```

在 Xcode 选择 `Ana` scheme、自己的签名团队和已连接设备，运行安装。模拟器调试需安装 iOS 27.0 运行时；登录测试保留本地签名，避免 Keychain 无法保存凭据。

[详细文档](docs/README.md)

客户端通过 [仓库内 XCSC 基础库](crates/client-foundation/ORIGIN.md) 使用队列、凭据和 FFI 支持。Android 应用 ID 与 iOS bundle ID 均为 `org.sarmg.ana`。
