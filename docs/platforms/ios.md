# iOS：安装、构建与排障

当前源码候选版本 **1.1.5**，仅支持最新稳定系统系列 iOS 27.0 及以上的设备。源码构建在 Mac 上完成；macOS 是开发环境，xszc 没有 macOS 桌面应用。当前候选尚未发布，下文下载链接指向已公开的 1.1.4；候选改动需同版源码构建。

安装后按[第一次备份](../getting-started.md)登录和验证。两端共享的备份设置、任务操作和数据维护分别见[配置](../configuration.md)、[日常使用](../usage.md)及[维护应用数据](../operations.md)。

## 安装、更新与卸载

### 安装发行包

从 [v1.1.4 发行页](https://github.com/isarmg/xszc/releases/tag/v1.1.4)下载 `xszc-ios-1.1.4-unsigned.ipa` 与 `SHA256SUMS`。IPA 未签名。先计算 SHA-256，与同版 `SHA256SUMS` 对应行核对：

```sh
shasum -a 256 ./xszc-ios-1.1.4-unsigned.ipa
```

使用自己的有效 Apple 签名身份和适用于目标设备的 provisioning 完成签名，再通过 Xcode、Apple Configurator 或已有受管理部署流程安装。仓库提供未签名包，具体证书与设备准入由所选 Apple 部署方式决定。

### 从源码安装到设备

在有稳定版 Xcode 27.0、iOS 27.0 SDK、XcodeGen `2.46.0` 和 Rust `1.99.0` 的 Mac 上，按[本页构建步骤](#从源码构建)生成工程。选择 `Xszc` target 的 Signing & Capabilities，设置自己的 Team，连接并信任设备，按提示启用 Developer Mode 后运行。

配对会保存 Keychain 凭据。设备及模拟器测试均保留适用的签名和 entitlement；禁用签名可能导致 `-34018`，此时先修复签名，再处理登录。

### 更新与删除

使用同应用标识、兼容签名和 entitlement 的新版覆盖安装，检查原队列、登录状态和新上传。删除前先处理待传任务并退出。

“设置 → 通用 → iPhone 储存空间 → 媒体备份 → 删除 App”删除应用与私有传输数据；“卸载 App/Offload”保留文稿与数据。系统照片及服务端备份保留。Keychain 项可能继续存在，删除应用不等于撤销远端 Token；退役时另行处理设备授权。

## 从源码构建

准备 Rust `1.99.0`，先在仓库根目录运行[公共检查](../development.md#rust-和公共检查)，再执行以下命令。

在 Mac 上准备稳定版 Xcode 27.0、iOS 27.0 SDK、iOS 27.0 模拟器运行时和 XcodeGen `2.46.0`。CI 使用官方 `xcode-27` 镜像，明确选择 `/Applications/Xcode_27.app/Contents/Developer`，排除镜像中同时安装的 beta Xcode：

```sh
./scripts/verify-ios-toolchain.sh
rustup target add aarch64-apple-ios aarch64-apple-ios-sim
./scripts/build-ios-rust.sh
(cd clients/ios && xcodegen generate)
xcrun simctl list devices available
```

脚本生成实机和模拟器 Rust 库及 `XszcRust.xcframework`。从列表选择已安装 iOS 27.0 的模拟器 UUID，将占位值替换后运行：

```sh
simulator_udid="REPLACE_WITH_AVAILABLE_IOS27_SIMULATOR_UUID"
xcodebuild -project clients/ios/Xszc.xcodeproj -scheme Xszc   -sdk iphonesimulator -destination "platform=iOS Simulator,id=$simulator_udid" test
```

模拟器使用工程配置的本地临时签名，无需 Apple Developer 证书。保留签名以验证配对与 Keychain；禁用签名可能造成凭据保存失败。

专项检查：

```sh
./scripts/test-ios-system-directory.sh
python3 scripts/test-package-ios-ipa.py
```

真机安装时在 Xcode 选择自己的 Team 和设备，见[实机安装步骤](#从源码安装到设备)。工程配置来自 `clients/ios/project.yml`。

## 平台排障

- IPA 无法安装：核对 iOS 版本、签名、目标设备 provisioning 和 entitlement。
- Keychain `-34018`：先修复签名与 entitlement；若服务端已配对而本机未保存凭据，请管理员轮换实例码后重试。成功后应能保存和复用设备凭据。
- 后台备份：iOS 决定后台调度时机。前台重新打开后确认队列继续；不要将模拟器结果当作真机后台验收。

### 日志

通过 Xcode 的 Devices and Simulators 查看设备崩溃信息，或在 macOS Console 中选择设备并筛选应用。前台重新打开应用，可检查系统暂停后队列是否继续。

登录、上传、重试和数据保留问题统一见[排查问题](../troubleshooting.md)。发布前按[发布验证](../development.md#发布验证)核对版本和实际设备结果。

[选择其他平台](../README.md#选择平台)
